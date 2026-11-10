/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/gui/AnimationRouletteScreen.java and
 * src/main/java/com/elfmcys/ysm/client/gui/button/FlatColorButton.java, Apache License 2.0.
 * Changes: 1.7.10 GuiScreen and Tessellator; the state (path, pages, clicks) is in RouletteMenu; the config forms of
 * extra_animation_buttons are drawn here as simple flat widgets (checkbox, slider, radio) that read and write through
 * emote/ConfigForm on the local animator (upstream submits a config action to the server, which forwards it to the
 * viewers; here roaming variables a form writes are synced by the client network, other variables stay local);
 * the translated texts (labels, sub-page names, the tooltip with the description, form titles, descriptions and
 * labels) come from emote/RouletteText; the chat line on play is behind the client option
 * print_animation_roulette_msg (upstream's PrintAnimationRouletteMsg); the config panel has a title line.
 */
package com.akashic.futura.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.akashic.futura.anim.PlayerAnimator;
import com.akashic.futura.client.ClientModel;
import com.akashic.futura.client.ClientOptions;
import com.akashic.futura.client.EmoteKeys;
import com.akashic.futura.client.LocalEmotes;
import com.akashic.futura.client.PlayerModels;
import com.akashic.futura.emote.ConfigForm;
import com.akashic.futura.emote.RouletteMenu;
import com.akashic.futura.emote.RouletteText;
import com.akashic.futura.format.pojo.manifest.settings.ConfigForms;
import com.akashic.futura.format.pojo.manifest.settings.ExtraAnimationButton;
import com.akashic.futura.model.ModelLang;

/**
 * The extra-animation roulette: eight slices per page around the centre, the lock button in the middle, paging and
 * return buttons on the right. A click on a slice plays its animation or opens its sub-page; a slice with a config
 * button has an inner ring that shows the button's config forms on the right.
 */
public class AnimationRouletteScreen extends GuiScreen {

    private static final int COUNT = RouletteMenu.PER_PAGE;
    private static final int TEXT_RADIUS = 65;
    private static final int LOCK = 0;
    private static final int PAGE_UP = 1;
    private static final int PAGE_DOWN = 2;
    private static final int RETURN = 3;

    private final RouletteMenu menu;
    /** The model's texts in the language of the game when the roulette was opened. */
    private final RouletteText text;
    private int x;
    private int y;
    private int selected = -1;
    private int hoverConfig = -1;
    private ExtraAnimationButton config;
    /** The open config panel's title: the label of the slice that opened it. */
    private String configTitle;
    /** The config panel's widgets as last laid out (clicks hit these). */
    private final List<Row> rows = new ArrayList<>();
    private int configScroll;
    private int configContentHeight;
    /** The range being dragged, or null. */
    private Row dragging;

    /** One widget of the config panel. */
    private static final class Row {

        final ConfigForms form;
        final ConfigForm.Type type;
        /** A radio label's index, or -1. */
        final int label;
        int left, top, right, bottom;

        Row(ConfigForms form, ConfigForm.Type type, int label) {
            this.form = form;
            this.type = type;
            this.label = label;
        }

        boolean hit(int mx, int my) {
            return mx >= left && mx < right && my >= top && my < bottom;
        }
    }

    public AnimationRouletteScreen(RouletteMenu menu) {
        this.menu = menu;
        ClientModel model = PlayerModels.INSTANCE.localModel();
        this.text = new RouletteText(
            model == null ? ModelLang.EMPTY : model.info.lang,
            ModelLang.locale(Minecraft.getMinecraft().gameSettings.language));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        buttonList.clear();
        x = width / 2 - 70;
        y = height / 2 - 8;
        buttonList.add(new FlatButton(LOCK, x - 20, y - 10, 40, 20, "") {

            @Override
            public void drawButton(Minecraft mc, int mouseX, int mouseY) {
                displayString = I18n
                    .format(EmoteKeys.isLocked() ? "gui.futura3d.roulette.lock_on" : "gui.futura3d.roulette.lock_off");
                super.drawButton(mc, mouseX, mouseY);
            }
        });
        buttonList.add(new FlatButton(PAGE_UP, x + 125, y - 102, 30, 30, "<"));
        buttonList.add(new FlatButton(PAGE_DOWN, x + 240, y - 102, 30, 30, ">"));
        buttonList.add(new FlatButton(RETURN, x + 125, y - 70, 145, 22, I18n.format("gui.futura3d.roulette.return")));
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        switch (button.id) {
            case LOCK:
                EmoteKeys.switchLock();
                break;
            case PAGE_UP:
                menu.pageUp();
                break;
            case PAGE_DOWN:
                menu.pageDown();
                break;
            case RETURN:
                LocalEmotes.INSTANCE.apply(menu.back());
                break;
            default:
                break;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        StringBuilder path = new StringBuilder();
        for (String id : menu.path()) {
            if (path.length() > 0) {
                path.append(" > ");
            }
            path.append(text.pageName(menu, id));
        }
        drawCenteredString(
            fontRendererObj,
            I18n.format("gui.futura3d.roulette.path", path.toString()),
            x + 195,
            y - 100 - 12,
            0xFFFFFF);
        drawFans(mouseX, mouseY);
        drawTexts();
        drawRect(x + 157, y - 87, x + 238, y - 72, 0xCF000000);
        drawCenteredString(fontRendererObj, (menu.page() + 1) + "/" + menu.pageCount(), x + 197, y - 83, 0x55FFFF);
        super.drawScreen(mouseX, mouseY, partialTicks);
        if (!drawConfig(mouseX, mouseY)) {
            drawDescription(mouseX, mouseY);
        }
    }

    /** Upstream's {@code drawTooltips}: the hovered slice's description from the model's lang files, if any. */
    private void drawDescription(int mouseX, int mouseY) {
        List<RouletteMenu.Entry> entries = menu.pageEntries();
        if (selected < 0 || selected >= entries.size()) {
            return;
        }
        String desc = text.description(entries.get(selected));
        if (desc != null) {
            @SuppressWarnings("unchecked")
            List<String> tip = fontRendererObj.listFormattedStringToWidth(desc, 240);
            drawHoveringText(tip, mouseX, mouseY, fontRendererObj);
        }
    }

    private void drawFans(int mouseX, int mouseY) {
        List<RouletteMenu.Entry> entries = menu.pageEntries();
        if (entries.isEmpty()) {
            return;
        }
        float theta = (float) Math.atan2(mouseY - y, mouseX - x);
        if (theta < 0) {
            theta += (float) (Math.PI * 2);
        }
        float distance = (float) Math.sqrt((mouseY - y) * (mouseY - y) + (mouseX - x) * (mouseX - x));
        selected = -1;
        hoverConfig = -1;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        Tessellator t = Tessellator.instance;
        t.startDrawingQuads();
        for (int i = 0; i < entries.size(); i++) {
            float spacing = (float) (Math.PI / 90);
            float start = (float) (2 * Math.PI / COUNT) * i + spacing;
            float end = (float) (2 * Math.PI / COUNT) * (i + 1) - spacing;
            boolean inSlice = start < theta && theta < end;
            boolean hasConfig = entries.get(i)
                .hasConfig();
            boolean hovered = inSlice && 50 < distance && distance < 100;
            if (hovered) {
                selected = i;
                if (hasConfig) {
                    fan(t, 50, 115, start, end, 0xF0FFB100);
                    fan(t, 25, 50, start, end, 0x90000000);
                } else {
                    fan(t, 25, 115, start, end, 0xF0FFB100);
                }
            } else {
                fan(t, 25, 105, start, end, 0x90000000);
            }
            if (hasConfig) {
                if (inSlice && 20 < distance && distance < 50) {
                    fan(t, 15, 50, start, end, 0xF000CEFF);
                    hoverConfig = i;
                } else {
                    fan(t, 25, 50, start, end, 0x7000CEFF);
                }
            }
        }
        t.draw();
        GL11.glEnable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void fan(Tessellator t, float rIn, float rOut, float start, float end, int color) {
        t.setColorRGBA_I(color & 0xFFFFFF, color >>> 24);
        double cs = Math.cos(start), ss = Math.sin(start), ce = Math.cos(end), se = Math.sin(end);
        t.addVertex(x + rOut * cs, y + rOut * ss, zLevel);
        t.addVertex(x + rIn * cs, y + rIn * ss, zLevel);
        t.addVertex(x + rIn * ce, y + rIn * se, zLevel);
        t.addVertex(x + rOut * ce, y + rOut * se, zLevel);
    }

    private void drawTexts() {
        List<RouletteMenu.Entry> entries = menu.pageEntries();
        boolean keys = menu.showsKeys();
        float angle = (float) (Math.PI / COUNT);
        int lineHeight = fontRendererObj.FONT_HEIGHT;
        for (int i = 0; i < entries.size(); i++) {
            RouletteMenu.Entry entry = entries.get(i);
            int xPos = (int) (x + TEXT_RADIUS * Math.cos(angle));
            int yPos = (int) (y + TEXT_RADIUS * Math.sin(angle) - lineHeight / 2f);
            if (entry.hasConfig()) {
                drawCenteredString(
                    fontRendererObj,
                    EnumChatFormatting.GOLD + "\u2699",
                    (int) (x + 35 * Math.cos(angle)),
                    (int) (y + 35 * Math.sin(angle) - lineHeight / 2f),
                    0xFFFFFF);
            }
            String label = text.label(entry);
            String name = entry.isClassify() ? EnumChatFormatting.RED + label : label;
            @SuppressWarnings("unchecked")
            List<String> lines = fontRendererObj.listFormattedStringToWidth(name, 50);
            int lineY = yPos - lines.size() * lineHeight + 2;
            if (!keys) {
                lineY += lineHeight;
            }
            for (String line : lines) {
                drawCenteredString(fontRendererObj, line, xPos, lineY, 0xF3EFE0);
                lineY += lineHeight;
            }
            if (keys && entry.index < EmoteKeys.EXTRA.length) {
                KeyBinding key = EmoteKeys.EXTRA[entry.index];
                String keyName = key.getKeyCode() == 0 ? I18n.format("key.futura3d.extra_animation.none")
                    : GameSettings.getKeyDisplayString(key.getKeyCode());
                drawCenteredString(
                    fontRendererObj,
                    EnumChatFormatting.YELLOW + "[ " + keyName + EnumChatFormatting.YELLOW + " ]",
                    xPos,
                    yPos + 4,
                    0xF3EFE0);
            }
            angle += (float) (2 * Math.PI / COUNT);
        }
    }

    private static final int PANEL_W = 145;
    private static final int PANEL_H = 156;
    private static final int CONTENT_TOP = 16;
    private static final int CONTENT_BOTTOM = 152;

    /** Opens a config button's panel, as a click on its inner ring does (also for the harness). */
    public void openConfig(ExtraAnimationButton button) {
        config = button;
        configScroll = 0;
        configTitle = button.name;
        for (RouletteMenu.Entry entry : menu.entries()) {
            if (entry.config == button) {
                configTitle = text.label(entry);
                break;
            }
        }
    }

    /** The local animator's Molang context, or null (no model). */
    private static com.akashic.futura.molang.MolangContext context() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || !PlayerModels.INSTANCE.active()) {
            return null;
        }
        PlayerAnimator a = PlayerModels.INSTANCE.animator(mc.thePlayer);
        return a == null ? null : a.context();
    }

    /**
     * The open config button's forms, editable: a checkbox writes 1 or 0, a slider its value snapped to the step, a
     * radio label runs its action. The mouse wheel over the panel scrolls it; the description shows on hover.
     *
     * @return whether a form's description was drawn as the tooltip
     */
    private boolean drawConfig(int mouseX, int mouseY) {
        rows.clear();
        if (config == null) {
            return false;
        }
        com.akashic.futura.molang.MolangContext ctx = context();
        int left = x + 125, top = y - 46;
        drawRect(left, top, left + PANEL_W, top + PANEL_H, 0xEF434242);
        drawString(
            fontRendererObj,
            EnumChatFormatting.GOLD + trim(configTitle == null ? config.name : configTitle, PANEL_W - 8),
            left + 4,
            top + 4,
            0xFFFFFF);
        int lineH = fontRendererObj.FONT_HEIGHT;
        int rowY = top + CONTENT_TOP - configScroll;
        int contentTop = top + CONTENT_TOP, contentBottom = top + CONTENT_BOTTOM;
        Row hovered = null;
        int[] indices = RouletteText.formIndices(config.configForms);
        for (int n = 0; n < config.configForms.size(); n++) {
            ConfigForms form = config.configForms.get(n);
            if (form == null) {
                continue;
            }
            ConfigForm.Type type = ConfigForm.type(form);
            String title = text.formTitle(config, indices[n], form);
            switch (type) {
                case CHECKBOX: {
                    Row r = row(form, type, -1, left + 2, rowY, left + PANEL_W - 2, rowY + 13);
                    if (visible(r, contentTop, contentBottom)) {
                        boolean on = ctx != null && ConfigForm.readBool(form, ctx);
                        boolean over = r.hit(mouseX, mouseY);
                        drawRect(r.left + 2, r.top + 2, r.left + 11, r.top + 11, over ? 0xFFCFCFCF : 0xFF9A9A9A);
                        drawRect(r.left + 3, r.top + 3, r.left + 10, r.top + 10, on ? 0xFF2AA0FF : 0xFF2B2B2B);
                        drawString(fontRendererObj, trim(title, PANEL_W - 20), r.left + 14, r.top + 3, 0xE0E0E0);
                        rows.add(r);
                        if (over) {
                            hovered = r;
                        }
                    }
                    rowY += 14;
                    break;
                }
                case RANGE: {
                    Row titleRow = row(form, ConfigForm.Type.OTHER, -1, left + 2, rowY, left + PANEL_W - 2, rowY + 11);
                    if (visible(titleRow, contentTop, contentBottom)) {
                        drawString(fontRendererObj, trim(title, PANEL_W - 8), left + 4, rowY + 1, 0xC0C0C0);
                        if (titleRow.hit(mouseX, mouseY)) {
                            hovered = titleRow;
                        }
                    }
                    Row r = row(form, type, -1, left + 4, rowY + 11, left + PANEL_W - 4, rowY + 24);
                    if (visible(r, contentTop, contentBottom)) {
                        float value = ctx == null ? (float) ConfigForm.min(form) : ConfigForm.readFloat(form, ctx);
                        float f = ConfigForm.fraction(form, value);
                        boolean over = r.hit(mouseX, mouseY) || dragging != null && dragging.form == form;
                        drawRect(r.left, r.top, r.right, r.bottom, over ? 0xCF5A5A5A : 0xCF2B2B2B);
                        int knob = r.left + Math.round(f * (r.right - r.left - 4));
                        drawRect(r.left, r.top, knob + 2, r.bottom, 0x9F2AA0FF);
                        drawRect(knob, r.top, knob + 4, r.bottom, 0xFFE0E0E0);
                        drawCenteredString(
                            fontRendererObj,
                            ConfigForm.format(value),
                            (r.left + r.right) / 2,
                            r.top + (r.bottom - r.top - lineH) / 2 + 1,
                            0xFFFFFF);
                        rows.add(r);
                        if (r.hit(mouseX, mouseY)) {
                            hovered = r;
                        }
                    }
                    rowY += 26;
                    break;
                }
                case RADIO: {
                    Row titleRow = row(form, ConfigForm.Type.OTHER, -1, left + 2, rowY, left + PANEL_W - 2, rowY + 11);
                    if (visible(titleRow, contentTop, contentBottom)) {
                        drawString(fontRendererObj, trim(title, PANEL_W - 8), left + 4, rowY + 1, 0xC0C0C0);
                        if (titleRow.hit(mouseX, mouseY)) {
                            hovered = titleRow;
                        }
                    }
                    rowY += 12;
                    List<String> labels = ConfigForm.labels(form);
                    for (int i = 0; i < labels.size(); i++) {
                        labels.set(i, text.formLabel(config, indices[n], i, labels.get(i)));
                    }
                    int widest = 0;
                    for (String label : labels) {
                        widest = Math.max(widest, fontRendererObj.getStringWidth(label) + 14);
                    }
                    int perLine = Math.max(1, (PANEL_W - 8) / Math.max(1, widest));
                    int cell = (PANEL_W - 8) / perLine;
                    int selected = ctx == null ? 0 : ConfigForm.radioSelected(form, ctx);
                    for (int i = 0; i < labels.size(); i++) {
                        int cx = left + 4 + (i % perLine) * cell;
                        int cy = rowY + (i / perLine) * 13;
                        Row r = row(form, type, i, cx, cy, cx + cell, cy + 12);
                        if (visible(r, contentTop, contentBottom)) {
                            boolean over = r.hit(mouseX, mouseY);
                            drawRect(r.left, r.top + 2, r.left + 9, r.top + 11, over ? 0xFFCFCFCF : 0xFF9A9A9A);
                            drawRect(
                                r.left + 1,
                                r.top + 3,
                                r.left + 8,
                                r.top + 10,
                                i == selected ? 0xFF2AA0FF : 0xFF2B2B2B);
                            drawString(
                                fontRendererObj,
                                trim(labels.get(i), cell - 13),
                                r.left + 12,
                                r.top + 3,
                                0xE0E0E0);
                            rows.add(r);
                            if (over) {
                                hovered = r;
                            }
                        }
                    }
                    rowY += ((labels.size() + perLine - 1) / perLine) * 13 + 3;
                    break;
                }
                default: {
                    @SuppressWarnings("unchecked")
                    List<String> lines = fontRendererObj.listFormattedStringToWidth(form.type + ": " + title, 137);
                    for (String line : lines) {
                        if (rowY >= contentTop && rowY + lineH <= contentBottom) {
                            drawString(fontRendererObj, line, left + 4, rowY, 0x909090);
                        }
                        rowY += lineH + 1;
                    }
                    break;
                }
            }
        }
        configContentHeight = rowY + configScroll - contentTop;
        int maxScroll = Math.max(0, configContentHeight - (CONTENT_BOTTOM - CONTENT_TOP));
        if (configScroll > maxScroll) {
            configScroll = maxScroll;
        }
        if (ctx == null) {
            drawString(
                fontRendererObj,
                EnumChatFormatting.ITALIC + I18n.format("gui.futura3d.roulette.config_no_model"),
                left + 4,
                top + PANEL_H - 10,
                0x909090);
        }
        if (hovered != null) {
            int f = config.configForms.indexOf(hovered.form);
            String desc = text.formDescription(config, f < 0 ? -1 : indices[f], hovered.form);
            if (!desc.isEmpty()) {
                @SuppressWarnings("unchecked")
                List<String> tip = fontRendererObj.listFormattedStringToWidth(desc, 200);
                drawHoveringText(tip, mouseX, mouseY, fontRendererObj);
                return true;
            }
        }
        return false;
    }

    private static Row row(ConfigForms form, ConfigForm.Type type, int label, int l, int t, int r, int b) {
        Row row = new Row(form, type, label);
        row.left = l;
        row.top = t;
        row.right = r;
        row.bottom = b;
        return row;
    }

    private static boolean visible(Row r, int top, int bottom) {
        return r.top >= top && r.bottom <= bottom;
    }

    private String trim(String text, int width) {
        return fontRendererObj.getStringWidth(text) <= width ? text
            : fontRendererObj.trimStringToWidth(text, Math.max(0, width - 6)) + "..";
    }

    /** A click in the config panel: true when a widget took it. */
    private boolean clickConfig(int mouseX, int mouseY) {
        com.akashic.futura.molang.MolangContext ctx = context();
        if (ctx == null) {
            return false;
        }
        for (Row r : rows) {
            if (!r.hit(mouseX, mouseY)) {
                continue;
            }
            switch (r.type) {
                case CHECKBOX:
                    ConfigForm.writeBool(r.form, ctx, !ConfigForm.readBool(r.form, ctx));
                    click();
                    return true;
                case RANGE:
                    dragging = r;
                    slide(r, mouseX, ctx);
                    return true;
                case RADIO:
                    ConfigForm.clickLabel(r.form, ctx, r.label);
                    click();
                    return true;
                default:
                    return false;
            }
        }
        return false;
    }

    private static void slide(Row r, int mouseX, com.akashic.futura.molang.MolangContext ctx) {
        double f = (mouseX - r.left - 2) / (double) Math.max(1, r.right - r.left - 4);
        ConfigForm.writeFloat(r.form, ctx, ConfigForm.atFraction(r.form, f));
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
        if (dragging != null) {
            com.akashic.futura.molang.MolangContext ctx = context();
            if (ctx != null) {
                slide(dragging, mouseX, ctx);
            }
            return;
        }
        super.mouseClickMove(mouseX, mouseY, button, timeSinceLastClick);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int state) {
        if (state != -1) {
            dragging = null;
        }
        super.mouseMovedOrUp(mouseX, mouseY, state);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button == 0 && config != null && clickConfig(mouseX, mouseY)) {
            return;
        }
        if (selected >= 0) {
            click();
            RouletteMenu.Action action = menu.click(selected);
            if (action.type == RouletteMenu.Action.Type.PLAY && mc.thePlayer != null
                && ClientOptions.INSTANCE.printRouletteMessage()) {
                // upstream clickDefault: the animation's key, as the model names it
                mc.thePlayer
                    .addChatMessage(new ChatComponentTranslation("message.futura3d.roulette.play", action.name));
            }
            LocalEmotes.INSTANCE.apply(action);
            return;
        }
        if (hoverConfig >= 0) {
            RouletteMenu.Action action = menu.clickConfig(hoverConfig);
            if (action.type == RouletteMenu.Action.Type.CONFIG) {
                click();
                RouletteMenu.Entry entry = menu.pageEntries()
                    .get(hoverConfig);
                openConfig(entry.config);
                configTitle = text.label(entry);
            }
            return;
        }
        super.mouseClicked(mouseX, mouseY, button);
    }

    private void click() {
        mc.getSoundHandler()
            .playSound(PositionedSoundRecord.func_147674_a(new ResourceLocation("gui.button.press"), 1.0F));
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int mouseX = Mouse.getEventX() * width / mc.displayWidth;
            if (config != null && mouseX >= x + 125 && mouseX < x + 125 + PANEL_W) {
                configScroll = Math.max(0, configScroll + (wheel < 0 ? 14 : -14));
            } else if (mouseX < x + 110) {
                if (wheel < 0) {
                    menu.pageDown();
                } else {
                    menu.pageUp();
                }
            }
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == EmoteKeys.ROULETTE.getKeyCode() && keyCode != 0) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** Upstream's {@code FlatColorButton}: a flat dark rectangle, lighter when hovered. */
    static class FlatButton extends GuiButton {

        FlatButton(int id, int x, int y, int width, int height, String text) {
            super(id, x, y, width, height, text);
        }

        @Override
        public void drawButton(Minecraft mc, int mouseX, int mouseY) {
            if (!visible) {
                return;
            }
            boolean hovered = mouseX >= xPosition && mouseY >= yPosition
                && mouseX < xPosition + width
                && mouseY < yPosition + height;
            drawRect(xPosition, yPosition, xPosition + width, yPosition + height, hovered ? 0xCF5A5A5A : 0xCF2B2B2B);
            drawCenteredString(
                mc.fontRenderer,
                displayString,
                xPosition + width / 2,
                yPosition + (height - 8) / 2,
                enabled ? 0xFFFFFF : 0xA0A0A0);
        }
    }
}
