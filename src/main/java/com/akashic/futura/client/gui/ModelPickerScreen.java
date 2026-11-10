/*
 * The layout (preview on the left, a grid of model cards with paging on the right, the texture choice and the licence
 * under the preview) follows Yes Steve Model's legacy PlayerModelScreen, PlayerTextureScreen and ModelButton
 * (https://github.com/YesSteveModel/YesSteveModel, legacy branch, BSD 3-Clause License, Copyright (c) 2025, Yes Steve
 * Model Dev Group). Written anew for the 1.7.10 GuiScreen; no code is copied.
 */
package com.akashic.futura.client.gui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.I18n;
import net.minecraft.util.EnumChatFormatting;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.akashic.futura.anim.BonePose;
import com.akashic.futura.anim.PlayerAnimator;
import com.akashic.futura.anim.PlayerSnapshot;
import com.akashic.futura.client.ClientModel;
import com.akashic.futura.client.ModelRegistry;
import com.akashic.futura.client.ModelSelection;
import com.akashic.futura.client.PlayerModels;
import com.akashic.futura.geo.Mat4;
import com.akashic.futura.geo.ModelFraming;
import com.akashic.futura.geo.PoseMatrices;
import com.akashic.futura.model.ModelInfo;
import com.akashic.futura.model.ModelLang;

/**
 * The model picker: every {@link ModelRegistry} model as a card with a small animated preview, its name and licence
 * (locked ones greyed out), a large rotating preview of the chosen card with its texture choice, licence and authors,
 * and a button that asks {@link ModelSelection#requestLocal} for it. Models load in the background as their page is
 * shown. Names, tips, authors and texture names come from the models' language files in the game's language when the
 * picker is opened ({@link ModelInfo#name(String)}...), else from their {@code ysm.json}.
 */
public class ModelPickerScreen extends GuiScreen {

    private static final int PREV_PAGE = 0;
    private static final int NEXT_PAGE = 1;
    private static final int PREV_TEXTURE = 2;
    private static final int NEXT_TEXTURE = 3;
    private static final int CONFIRM = 4;

    private static final int LEFT_W = 135;
    private static final int CELL_W = 52;
    private static final int CELL_H = 90;
    private static final int GAP = 3;
    private static final int ROWS = 2;
    private static final int H = 235;

    private static final int TEXT = 0xF3EFE0;
    private static final int PANEL = 0xF0222222;
    private static final int CARD = 0xFF434242;

    private List<String> keys = new ArrayList<>();
    private java.util.function.Predicate<String> filter = key -> true;
    /** How many keys the registry had at the last layout: a change lays the page out again. */
    private int known;
    private int cols = 5;
    private int page;
    private int x0, y0, w;
    private String focused;
    private String texture;
    private String status;
    private long statusUntil;
    private float yaw;
    private boolean dragging;
    private int lastDragX;
    private long lastClick;
    private String lastClickKey;
    private final long opened = System.nanoTime();
    private final Map<ClientModel, Preview> cards = new HashMap<>();
    private final Map<ClientModel, Preview> large = new HashMap<>();
    private final PlayerSnapshot snapshot = new PlayerSnapshot();
    /** The models' locale: the game's language when the picker was opened. */
    private final String locale = ModelLang.locale(Minecraft.getMinecraft().gameSettings.language);
    private GuiButton prevTexture, nextTexture, confirm, prevPage, nextPage;

    public ModelPickerScreen() {
        snapshot.renderingInInventory = true;
        focused = PlayerModels.INSTANCE.keyOf(Minecraft.getMinecraft().thePlayer);
        texture = PlayerModels.INSTANCE.textureOf(Minecraft.getMinecraft().thePlayer);
    }

    /** Shows only the models whose key passes (a licence filter, the development harness). */
    public ModelPickerScreen only(java.util.function.Predicate<String> filter) {
        this.filter = filter;
        return this;
    }

    /** Opens on a model's card with a texture (development harness). */
    public ModelPickerScreen focus(String key, String texture) {
        this.focused = key;
        this.texture = texture;
        page = -1;
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        List<String> all = ModelRegistry.INSTANCE.keys();
        known = all.size();
        keys = new ArrayList<>();
        for (String key : all) {
            if (filter.test(key)) {
                keys.add(key);
            }
        }
        cols = Math.max(2, Math.min(5, (width - LEFT_W - 3 - 10) / (CELL_W + GAP)));
        w = LEFT_W + 3 + 5 + cols * (CELL_W + GAP);
        x0 = (width - w) / 2;
        y0 = Math.max(0, (height - H) / 2);
        if (page < 0 || page > pages() - 1) {
            int index = keys.indexOf(focused);
            page = index < 0 ? 0 : index / perPage();
        }
        buttonList.clear();
        int right = x0 + LEFT_W + 3;
        int rightW = w - LEFT_W - 3;
        prevPage = new AnimationRouletteScreen.FlatButton(PREV_PAGE, right + 8, y0 + H - 18, 40, 14, "<");
        nextPage = new AnimationRouletteScreen.FlatButton(NEXT_PAGE, right + rightW - 48, y0 + H - 18, 40, 14, ">");
        prevTexture = new AnimationRouletteScreen.FlatButton(PREV_TEXTURE, x0 + 5, y0 + 156, 14, 12, "<");
        nextTexture = new AnimationRouletteScreen.FlatButton(NEXT_TEXTURE, x0 + LEFT_W - 19, y0 + 156, 14, 12, ">");
        confirm = new AnimationRouletteScreen.FlatButton(CONFIRM, x0 + 5, y0 + H - 20, LEFT_W - 10, 16, "");
        buttonList.add(prevPage);
        buttonList.add(nextPage);
        buttonList.add(prevTexture);
        buttonList.add(nextTexture);
        buttonList.add(confirm);
        updateButtons();
    }

    private int perPage() {
        return cols * ROWS;
    }

    private int pages() {
        return Math.max(1, (keys.size() + perPage() - 1) / perPage());
    }

    private void updateButtons() {
        prevPage.enabled = page > 0;
        nextPage.enabled = page < pages() - 1;
        ModelInfo info = ModelRegistry.INSTANCE.info(focused);
        boolean several = info != null && info.textures.size() > 1;
        prevTexture.visible = several;
        nextTexture.visible = several;
        boolean locked = ModelRegistry.INSTANCE.isLocked(focused);
        boolean failed = ModelRegistry.INSTANCE.state(focused) == ModelRegistry.State.FAILED;
        boolean inUse = focused != null && focused.equals(inUseKey())
            && (info == null || info.texture(texture)
                .equals(info.texture(PlayerModels.INSTANCE.textureOf(mc.thePlayer))));
        confirm.enabled = focused != null && !locked && !failed && !inUse;
        confirm.displayString = I18n.format(
            locked ? "gui.futura3d.picker.locked"
                : failed ? "gui.futura3d.picker.failed"
                    : inUse ? "gui.futura3d.picker.in_use" : "gui.futura3d.picker.use");
    }

    private String inUseKey() {
        return PlayerModels.INSTANCE.keyOf(mc.thePlayer);
    }

    @Override
    public void updateScreen() {
        if (ModelRegistry.INSTANCE.keys()
            .size() != known) {
            // A server model arrived or the server models were cleared.
            initGui();
        }
        // Previews of models that were freed meanwhile are dropped.
        for (Map<ClientModel, Preview> previews : java.util.Arrays.asList(cards, large)) {
            for (Iterator<ClientModel> it = previews.keySet()
                .iterator(); it.hasNext();) {
                if (it.next()
                    .released()) {
                    it.remove();
                }
            }
        }
        updateButtons();
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case PREV_PAGE:
                page = Math.max(0, page - 1);
                break;
            case NEXT_PAGE:
                page = Math.min(pages() - 1, page + 1);
                break;
            case PREV_TEXTURE:
            case NEXT_TEXTURE:
                cycleTexture(button.id == NEXT_TEXTURE ? 1 : -1);
                break;
            case CONFIRM:
                confirm();
                break;
            default:
                break;
        }
        updateButtons();
    }

    private void cycleTexture(int step) {
        ModelInfo info = ModelRegistry.INSTANCE.info(focused);
        if (info == null || info.textures.isEmpty()) {
            return;
        }
        int i = info.textures.indexOf(info.texture(texture));
        int n = info.textures.size();
        texture = info.textures.get(((i + step) % n + n) % n);
    }

    private void confirm() {
        if (focused == null || ModelRegistry.INSTANCE.isLocked(focused)) {
            return;
        }
        ModelInfo info = ModelRegistry.INSTANCE.info(focused);
        String chosen = info == null ? texture : info.texture(texture);
        if (info != null && chosen.equals(info.defaultTexture)) {
            chosen = null;
        }
        boolean server = ModelSelection.INSTANCE.hasHandler();
        ModelSelection.INSTANCE.requestLocal(focused, chosen);
        status = I18n.format(server ? "gui.futura3d.picker.requested" : "gui.futura3d.picker.applied");
        statusUntil = System.currentTimeMillis() + 3000;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        super.mouseClicked(mouseX, mouseY, button);
        if (button != 0) {
            return;
        }
        String key = cellAt(mouseX, mouseY);
        if (key != null) {
            long now = System.currentTimeMillis();
            boolean twice = key.equals(lastClickKey) && now - lastClick < 400;
            lastClick = now;
            lastClickKey = key;
            if (!key.equals(focused)) {
                focused = key;
                texture = key.equals(inUseKey()) ? PlayerModels.INSTANCE.textureOf(mc.thePlayer) : null;
                mc.getSoundHandler()
                    .playSound(
                        net.minecraft.client.audio.PositionedSoundRecord
                            .func_147674_a(new net.minecraft.util.ResourceLocation("gui.button.press"), 1.0F));
            } else if (twice) {
                confirm();
            }
            updateButtons();
        } else if (inPreview(mouseX, mouseY)) {
            dragging = true;
            lastDragX = mouseX;
        }
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long time) {
        if (dragging) {
            yaw += (mouseX - lastDragX) * 2;
            lastDragX = mouseX;
        }
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        super.mouseMovedOrUp(mouseX, mouseY, state);
        if (state >= 0) {
            dragging = false;
        }
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            page = wheel > 0 ? Math.max(0, page - 1) : Math.min(pages() - 1, page + 1);
            updateButtons();
        }
    }

    private boolean inPreview(int mx, int my) {
        return mx >= x0 && mx < x0 + LEFT_W && my >= y0 + 24 && my < y0 + 152;
    }

    private int cellX(int i) {
        return x0 + LEFT_W + 3 + 5 + (i % cols) * (CELL_W + GAP);
    }

    private int cellY(int i) {
        return y0 + 24 + (i / cols) * (CELL_H + GAP);
    }

    private String cellAt(int mx, int my) {
        for (int i = 0; i < perPage(); i++) {
            int index = page * perPage() + i;
            if (index >= keys.size()) {
                break;
            }
            int cx = cellX(i), cy = cellY(i);
            if (mx >= cx && mx < cx + CELL_W && my >= cy && my < cy + CELL_H) {
                return keys.get(index);
            }
        }
        return null;
    }

    /** The centre of a model's card on the shown page, in screen coordinates (development harness), or null. */
    public int[] cardCenter(String key) {
        int i = keys.indexOf(key) - page * perPage();
        return i < 0 || i >= perPage() ? null : new int[] { cellX(i) + CELL_W / 2, cellY(i) + CELL_H / 2 };
    }

    @Override
    public void onGuiClosed() {
        cards.clear();
        large.clear();
        // The page previews loaded models nobody wears: free them (they load again when shown or chosen).
        PlayerModels.INSTANCE.releaseUnusedModels();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ---- Drawing ----

    @Override
    public void drawScreen(int mouseX, int mouseY, float pt) {
        drawDefaultBackground();
        int right = x0 + LEFT_W + 3;
        drawRect(x0, y0, x0 + LEFT_W, y0 + H, PANEL);
        drawRect(right, y0, x0 + w, y0 + H, PANEL);

        float ticks = (System.nanoTime() - opened) / 50_000_000f;
        String inUse = inUseKey();
        String hovered = cellAt(mouseX, mouseY);

        // Cards and their previews.
        for (int i = 0; i < perPage(); i++) {
            int index = page * perPage() + i;
            if (index >= keys.size()) {
                break;
            }
            String key = keys.get(index);
            int cx = cellX(i), cy = cellY(i);
            drawRect(cx, cy, cx + CELL_W, cy + CELL_H, CARD);
            ClientModel model = ModelRegistry.INSTANCE.get(key);
            if (model != null) {
                scissor(cx, cy, CELL_W, CELL_H - 20);
                // Cards play the model's preview animation (an authored GUI pose faces the viewer as authored).
                boolean authored = !model.info.previewAnimation.isEmpty();
                drawPreview(
                    cards,
                    model,
                    key.equals(focused) ? texture : null,
                    cx,
                    cy + 10,
                    CELL_W,
                    CELL_H - 30,
                    ticks,
                    authored ? 0 : -20);
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
        }
        // The large preview of the focused model: idle, turning (drag to turn it by hand), as upstream's texture
        // screen shows it; disable_preview_rotation stops the turn.
        ClientModel big = focused == null ? null : ModelRegistry.INSTANCE.get(focused);
        ModelInfo focusedInfo = ModelRegistry.INSTANCE.info(focused);
        if (big != null) {
            scissor(x0 + 2, y0 + 24, LEFT_W - 4, 128);
            float spin = focusedInfo != null && focusedInfo.disablePreviewRotation ? 0 : ticks * 4;
            drawPreview(large, big, texture, x0 + 2, y0 + 26, LEFT_W - 4, 124, ticks, yaw + spin - 20);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        // Text above the models.
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

        for (int i = 0; i < perPage(); i++) {
            int index = page * perPage() + i;
            if (index >= keys.size()) {
                break;
            }
            drawCard(
                keys.get(index),
                cellX(i),
                cellY(i),
                keys.get(index)
                    .equals(inUse),
                keys.get(index)
                    .equals(hovered));
        }
        drawDetails(focusedInfo, big != null);

        String title = I18n.format("gui.futura3d.picker.title", keys.size());
        fontRendererObj.drawStringWithShadow(title, right + 8, y0 + 8, TEXT);
        String pageInfo = (page + 1) + " / " + pages();
        drawCenteredString(fontRendererObj, pageInfo, right + (x0 + w - right) / 2, y0 + H - 15, TEXT);
        if (status != null && System.currentTimeMillis() < statusUntil) {
            drawCenteredString(fontRendererObj, status, right + (x0 + w - right) / 2, y0 + 8, 0x55FF55);
        }
        super.drawScreen(mouseX, mouseY, pt);
        if (hovered != null) {
            drawCardTooltip(hovered, mouseX, mouseY);
        }
    }

    private void drawCard(String key, int cx, int cy, boolean inUse, boolean hovered) {
        ModelInfo info = ModelRegistry.INSTANCE.info(key);
        ModelRegistry.State state = ModelRegistry.INSTANCE.state(key);
        if (state != ModelRegistry.State.READY) {
            String text = state == ModelRegistry.State.FAILED ? "✖" : I18n.format("gui.futura3d.picker.loading");
            drawCenteredString(fontRendererObj, text, cx + CELL_W / 2, cy + 30, 0xA0A0A0);
            ModelRegistry.INSTANCE.request(key);
        }
        String name = info == null ? key.substring(key.indexOf(':') + 1) : info.name(locale);
        List<String> lines = wrap(name, CELL_W - 4, 2);
        int ly = cy + CELL_H - (lines.size() > 1 ? 19 : 14);
        for (String line : lines) {
            drawCenteredString(fontRendererObj, line, cx + CELL_W / 2, ly, TEXT);
            ly += 9;
        }
        if (info != null) {
            String tag = info.shortLicense();
            int tw = fontRendererObj.getStringWidth(tag);
            if (tw > CELL_W - 4) {
                tag = fontRendererObj.trimStringToWidth(tag, CELL_W - 4);
                tw = fontRendererObj.getStringWidth(tag);
            }
            drawRect(cx + 1, cy + 1, cx + tw + 4, cy + 10, 0xC0000000);
            fontRendererObj.drawString(tag, cx + 3, cy + 2, licenseColor(info));
        }
        if (key.startsWith(ModelRegistry.SERVER)) {
            fontRendererObj.drawString("S", cx + CELL_W - 7, cy + 2, 0x55FFFF);
        }
        if (ModelRegistry.INSTANCE.isLocked(key)) {
            drawRect(cx, cy, cx + CELL_W, cy + CELL_H, 0xB0222222);
            drawCenteredString(
                fontRendererObj,
                I18n.format("gui.futura3d.picker.locked"),
                cx + CELL_W / 2,
                cy + 40,
                0xFF7777);
        }
        if (inUse) {
            border(cx, cy, CELL_W, CELL_H, 0xFF55FF55);
        }
        if (key.equals(focused)) {
            border(cx - 1, cy - 1, CELL_W + 2, CELL_H + 2, 0xFFF3EFE0);
        } else if (hovered) {
            border(cx, cy, CELL_W, CELL_H, 0xFFA0A0A0);
        }
    }

    private void drawDetails(ModelInfo info, boolean ready) {
        if (focused == null) {
            return;
        }
        String name = info == null ? focused : info.name(locale);
        List<String> title = wrap(name, LEFT_W - 8, 2);
        int ty = y0 + (title.size() > 1 ? 4 : 8);
        for (String line : title) {
            drawCenteredString(fontRendererObj, line, x0 + LEFT_W / 2, ty, TEXT);
            ty += 9;
        }
        String tips = info == null ? "" : info.tips(locale);
        if (title.size() == 1 && !tips.trim()
            .isEmpty()) {
            // One line of the tips under a one-line name (the card's tooltip has all of it).
            String line = fontRendererObj.trimStringToWidth(tips.replace('\n', ' '), LEFT_W - 8);
            drawCenteredString(fontRendererObj, line, x0 + LEFT_W / 2, ty, 0x909090);
        }
        if (!ready) {
            String text = ModelRegistry.INSTANCE.state(focused) == ModelRegistry.State.FAILED
                ? I18n.format("gui.futura3d.picker.failed")
                : I18n.format("gui.futura3d.picker.loading");
            drawCenteredString(fontRendererObj, text, x0 + LEFT_W / 2, y0 + 80, 0xA0A0A0);
        }
        if (info == null) {
            return;
        }
        // Texture choice.
        String tex = info.texture(texture);
        String texName = info.textureName(tex, locale);
        String texLabel = info.textures.size() > 1
            ? (info.textures.indexOf(tex) + 1) + "/" + info.textures.size() + " " + texName
            : texName;
        texLabel = fontRendererObj.trimStringToWidth(texLabel, LEFT_W - 44);
        drawCenteredString(fontRendererObj, texLabel, x0 + LEFT_W / 2, y0 + 158, 0xC0C0C0);
        // Licence, prominent: a coloured band.
        int ly = y0 + 172;
        drawRect(x0 + 4, ly - 2, x0 + LEFT_W - 4, ly + 10, 0xC0000000);
        String license = fontRendererObj.trimStringToWidth(info.license, LEFT_W - 12);
        drawCenteredString(fontRendererObj, license, x0 + LEFT_W / 2, ly, licenseColor(info));
        // Authors.
        if (!info.authors.isEmpty()) {
            String authors = I18n.format("gui.futura3d.picker.authors", String.join(", ", info.authors(locale)));
            int ay = y0 + 186;
            for (String line : wrap(authors, LEFT_W - 8, 2)) {
                drawCenteredString(fontRendererObj, line, x0 + LEFT_W / 2, ay, 0xA0A0A0);
                ay += 9;
            }
        }
    }

    private void drawCardTooltip(String key, int mx, int my) {
        ModelInfo info = ModelRegistry.INSTANCE.info(key);
        List<String> lines = new ArrayList<>();
        if (info == null) {
            lines.add(key);
        } else {
            lines.add(info.name(locale));
            String tips = info.tips(locale);
            if (!tips.trim()
                .isEmpty()) {
                for (String part : tips.split("\n")) {
                    for (Object line : fontRendererObj.listFormattedStringToWidth(part, 200)) {
                        lines.add(EnumChatFormatting.GRAY + String.valueOf(line));
                    }
                }
            }
            lines.add(EnumChatFormatting.DARK_GRAY + key);
            lines.add(I18n.format("gui.futura3d.picker.license", info.license));
            if (!info.licenseDesc.isEmpty()) {
                lines.addAll(fontRendererObj.listFormattedStringToWidth(info.licenseDesc, 200));
            }
            if (!info.authors.isEmpty()) {
                lines.addAll(
                    fontRendererObj.listFormattedStringToWidth(
                        I18n.format("gui.futura3d.picker.authors", String.join(", ", info.authors(locale))),
                        200));
            }
            if (info.textures.size() > 1) {
                lines.add(I18n.format("gui.futura3d.picker.textures", info.textures.size()));
            }
        }
        if (ModelRegistry.INSTANCE.isLocked(key)) {
            lines.add(EnumChatFormatting.RED + I18n.format("gui.futura3d.picker.locked_tip"));
        }
        // Vanilla flips the tooltip to the left of the pointer when it does not fit on the right; keep it on screen.
        int tw = 0;
        for (String line : lines) {
            tw = Math.max(tw, fontRendererObj.getStringWidth(line));
        }
        int tx = mx;
        if (mx + 12 + tw > width && mx - 16 - tw < 0) {
            tx = Math.max(0, width - tw - 16);
        }
        func_146283_a(lines, tx, my);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
    }

    private static int licenseColor(ModelInfo info) {
        switch (info.licenseKind()) {
            case PUBLIC_DOMAIN:
                return 0x55FF55;
            case OPEN:
                return 0x55FFFF;
            case NON_COMMERCIAL:
                return 0xFFAA00;
            default:
                return 0xFF5555;
        }
    }

    private List<String> wrap(String text, int width, int maxLines) {
        @SuppressWarnings("unchecked")
        List<String> lines = new ArrayList<>(fontRendererObj.listFormattedStringToWidth(text, width));
        if (lines.size() > maxLines) {
            List<String> cut = new ArrayList<>(lines.subList(0, maxLines));
            String last = cut.get(maxLines - 1);
            cut.set(maxLines - 1, fontRendererObj.trimStringToWidth(last, width - 6) + "..");
            return cut;
        }
        return lines;
    }

    private static void border(int x, int y, int w, int h, int color) {
        drawRect(x, y, x + w, y + 1, color);
        drawRect(x, y + h - 1, x + w, y + h, color);
        drawRect(x, y + 1, x + 1, y + h - 1, color);
        drawRect(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    private void scissor(int x, int y, int w, int h) {
        ScaledResolution res = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
        int s = res.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(x * s, mc.displayHeight - (y + h) * s, w * s, h * s);
    }

    /** One model preview: its animator, pose and camera envelope. */
    private static final class Preview {

        final PlayerAnimator animator;
        final PoseMatrices pose;
        final ModelFraming framing;

        Preview(ClientModel model, boolean previewAnimation) {
            animator = new PlayerAnimator(model.animations);
            String preview = model.info.previewAnimation;
            if (previewAnimation && !preview.isEmpty()) {
                animator.playExtra(preview);
            }
            pose = new PoseMatrices(model.loaded.main);
            framing = new ModelFraming(model.loaded.main);
        }
    }

    /**
     * Draws a model fitted into a screen rectangle, lit as {@code GuiInventory.func_147046_a} lights the player: its
     * preview animation ({@code preview_animation}) on the cards, idle in the large preview, turned by {@code yaw}
     * degrees, framed as upstream frames
     * its previews ({@link ModelFraming}).
     */
    private void drawPreview(Map<ClientModel, Preview> previews, ClientModel model, String textureName, int x, int y,
        int width, int height, float ticks, float yaw) {
        Preview preview = previews.get(model);
        if (preview == null) {
            preview = new Preview(model, previews == cards);
            previews.put(model, preview);
        }
        BonePose pose;
        try {
            snapshot.ticksExisted = (int) ticks;
            snapshot.partialTicks = ticks - (int) ticks;
            preview.animator.update(snapshot, ticks);
            pose = preview.animator.pose();
        } catch (RuntimeException e) {
            pose = null;
        }
        preview.pose.compute(pose);
        // Model space to view space (y up): the slight tilt of vanilla's inventory, the turn, the model's scale.
        float[] view = Mat4.identity();
        Mat4.rotateX(view, 0, (float) Math.toRadians(-10));
        Mat4.rotateY(view, 0, (float) Math.toRadians(180 + yaw));
        Mat4.scale(view, 0, model.loaded.widthScale, model.loaded.heightScale, model.loaded.widthScale);
        preview.framing.include(preview.pose, view);
        float[] camera = preview.framing.fit(width, height);
        if (camera == null) {
            return;
        }
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_COLOR_MATERIAL);
        GL11.glPushMatrix();
        GL11.glTranslatef(x + width / 2f, y + height / 2f, 100);
        GL11.glScalef(camera[0], -camera[0], camera[0]);
        GL11.glRotatef(135, 0, 1, 0);
        if (!model.loaded.manifest.properties.guiNoLighting) {
            RenderHelper.enableStandardItemLighting();
        }
        GL11.glRotatef(-135, 0, 1, 0);
        GL11.glTranslatef(-camera[1], -camera[2], -camera[3]);
        multiply(view);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
        PlayerModels.INSTANCE.drawModel(model, model.skin(textureName), preview.pose);
        GL11.glPopMatrix();
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        OpenGlHelper.setActiveTexture(OpenGlHelper.lightmapTexUnit);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        OpenGlHelper.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GL11.glPopAttrib();
    }

    private final java.nio.FloatBuffer matrix = org.lwjgl.BufferUtils.createFloatBuffer(16);

    private void multiply(float[] m) {
        matrix.clear();
        matrix.put(m, 0, 16);
        matrix.flip();
        GL11.glMultMatrix(matrix);
    }
}
