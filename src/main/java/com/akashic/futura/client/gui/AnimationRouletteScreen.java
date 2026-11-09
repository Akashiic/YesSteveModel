/*
 * Adapted from Yes Steve Model (https://github.com/YesSteveModel/YesSteveModel, branch dev/1.20, commit 15c2c25),
 * src/main/java/com/elfmcys/ysm/client/gui/AnimationRouletteScreen.java and
 * src/main/java/com/elfmcys/ysm/client/gui/button/FlatColorButton.java, Apache License 2.0.
 * Changes: 1.7.10 GuiScreen and Tessellator; the state (path, pages, clicks) is in RouletteMenu; the config forms of
 * extra_animation_buttons are listed but not editable yet; labels are not looked up in the model's lang files yet;
 * no chat message on play.
 */
package com.akashic.futura.client.gui;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import com.akashic.futura.client.EmoteKeys;
import com.akashic.futura.client.LocalEmotes;
import com.akashic.futura.emote.RouletteMenu;
import com.akashic.futura.format.pojo.manifest.settings.ConfigForms;
import com.akashic.futura.format.pojo.manifest.settings.ExtraAnimationButton;

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
    private int x;
    private int y;
    private int selected = -1;
    private int hoverConfig = -1;
    private ExtraAnimationButton config;

    public AnimationRouletteScreen(RouletteMenu menu) {
        this.menu = menu;
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
            path.append(id);
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
        drawConfig();
        super.drawScreen(mouseX, mouseY, partialTicks);
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
            String name = entry.isClassify() ? EnumChatFormatting.RED + entry.label : entry.label;
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

    /** Lists the open config button's forms; editing them (and {@code v.roaming} sync) comes later. */
    private void drawConfig() {
        if (config == null) {
            return;
        }
        int left = x + 125, top = y - 46;
        drawRect(left, top, left + 145, top + 156, 0xEF434242);
        drawString(fontRendererObj, EnumChatFormatting.GOLD + config.name, left + 4, top + 4, 0xFFFFFF);
        int lineY = top + 18;
        for (ConfigForms form : config.configForms) {
            @SuppressWarnings("unchecked")
            List<String> lines = fontRendererObj.listFormattedStringToWidth(form.type + ": " + form.title, 137);
            for (String line : lines) {
                if (lineY > top + 140) {
                    return;
                }
                drawString(fontRendererObj, line, left + 4, lineY, 0xC0C0C0);
                lineY += fontRendererObj.FONT_HEIGHT + 1;
            }
        }
        drawString(
            fontRendererObj,
            EnumChatFormatting.ITALIC + I18n.format("gui.futura3d.roulette.config_later"),
            left + 4,
            top + 144,
            0x909090);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (selected >= 0) {
            click();
            LocalEmotes.INSTANCE.apply(menu.click(selected));
            return;
        }
        if (hoverConfig >= 0) {
            RouletteMenu.Action action = menu.clickConfig(hoverConfig);
            if (action.type == RouletteMenu.Action.Type.CONFIG) {
                click();
                config = menu.pageEntries()
                    .get(hoverConfig).config;
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
            if (mouseX < x + 110) {
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
