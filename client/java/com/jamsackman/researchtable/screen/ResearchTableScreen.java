package com.jamsackman.researchtable.screen;

import com.jamsackman.researchtable.ResearchTableMod;
import com.jamsackman.researchtable.mixin.access.EnchantCompat;
import com.jamsackman.researchtable.client.ClientEnchantDescriptions;
import com.jamsackman.researchtable.client.ResearchClientState;
import com.jamsackman.researchtable.data.ResearchItems;
import com.jamsackman.researchtable.state.ResearchPersistentState;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.function.Function;

public class ResearchTableScreen extends HandledScreen<ResearchTableScreenHandler> {

    // --- Textures (we draw within 254x166) ---
    private static final Identifier BG_ENCHANTS   = new Identifier("researchtable", "textures/gui/enchants0.png");
    private static final Identifier BG_RESEARCH   = new Identifier("researchtable", "textures/gui/enchants1.png");
    private static final Identifier BG_IMBUING    = new Identifier("researchtable", "textures/gui/enchants2.png");
    private static final Identifier BG_IMBUE_SIDE = new Identifier("researchtable", "textures/gui/enchants2a.png");
    private static final Identifier TEX_EXP = new Identifier("researchtable", "textures/gui/expsprite.png");

    // --- Right attached panel geometry ---
    private static final int RIGHT_PANEL_OVERLAP = 24; // slight overlap so it visually joins
    private static final int RIGHT_PANEL_DRAW_W  = 100;

    // --- Imbue side panel scrolling (same behavior as enchant info panel) ---
    private int imbuePanelScroll = 0;        // pixels scrolled
    private int imbueContentHeight = 0;      // total pixel height of rows
    private static final int IMBUE_ROW_H_HDR = 12; // header rows
    private static final int IMBUE_ROW_H_ENCH = 11; // enchant rows

    // --- Research preview (tab 1) ---
    private static final int R_PREVIEW_PAD   = 6;
    private static final int R_WIN_X_OFFSET  = 118;
    private static final int R_WIN_Y_OFFSET  = 18;
    private static final int R_WIN_W         = 113;
    private static final int R_WIN_H         = 59;

    private static final int RESEARCH_LINE_H          = 12;
    private static final int RESEARCH_POINTS_PER_LEVEL= 100;

    private int researchScroll = 0;
    private int researchContentHeight = 0;

    // --- Enchants tab layout (tab 0) ---
    private static final int CONTENT_PAD_LEFT   = 46;
    private static final int CONTENT_PAD_TOP    = 19;
    private static final int CONTENT_PAD_RIGHT  = 0;
    private static final int CONTENT_PAD_BOTTOM = 1;
    private static final int PANEL_OFFSET_X     = -4;
    private static final int LIST_W             = 114;
    private static final int ROW_H              = 23;
    private static final int PANEL_GAP          = 0;
    private static final int APPLICABLE_COLS    = 4;

    private int panelScroll = 0;
    private int panelContentHeight = 0;

    // --- Tabs (0=Enchants, 1=Research, 2=Imbue) ---
    private int tab = 1;
    private int scroll = 0;

    // --- Colors (ARGB) ---
    private static final int COL_HEADER_TEXT   = 0xFF2B2F33;
    private static final int COL_TEXT          = 0xFFE0E0E0;
    private static final int COLOR_LOCKED      = 0xFF9AA0A6;
    private static final int COLOR_UNLOCKED    = 0xFFB6F2A2;
    private static final int COLOR_COMPLETE    = 0xFF47E5D2;
    private static final int COLOR_HOVER       = 0x669B8C6E;

    private static final int COL_GOLD          = 0xFFEBCB62;
    private static final int COL_LOCKED        = 0xFF9AA0A6;
    private static final int COL_DIMMED        = 0xFF606469;

    private static final int ROW_BG_BASE       = 0xBB51493A;
    private static final int ROW_BG_HEADER     = 0xAAA8A811;
    private static final int ROW_BG_HOVER      = 0xBBAC8C90;
    private static final int ROW_BG_SELECTED   = 0xAAC678BD;
    private static final int ROW_BG_INCOMPAT   = 0xAA222233;

    // --- Tab hitboxes (relative to GUI origin) ---
    private static final int TAB_X1=1,  TAB_X2=42;
    private static final int TAB0_Y1=5,  TAB0_Y2=44;
    private static final int TAB1_Y1=45, TAB1_Y2=84;
    private static final int TAB2_Y1=85, TAB2_Y2=125;

    // Where to render the costs below the crafting slots
    private static final int COST_AREA_X = 87;  // relative to GUI origin (x)
    private static final int COST_AREA_Y = 42;  // relative to GUI origin (y)
    private static final int ICON_SIZE   = 12;  // we’ll draw exp sprite at 12x12
    private static final int COL_HDR_BG = 0xCC202225;   // dark grey semi-opaque (header background)

    private static final int COL_OK = 0xFF91F991;   // ccf991 with full alpha
    private static final int COL_BAD = 0xFFFF3B3B;  // bright red for header when insufficient

    // --- UI state ---
    private ButtonWidget consumeBtn;
    private int hoveredRow = -1;
    private String selectedEnchId = null;
    private Enchantment selectedEnch = null;

    // Tracks which item is in the input slot so we can clear selections if it changes/vanishes
    private String lastInputSig = "";

    private static String inputSignature(ItemStack s) {
        if (s == null || s.isEmpty()) return "empty";
        var id = Registries.ITEM.getId(s.getItem());
        String enchNbt = s.getEnchantments() != null ? s.getEnchantments().toString() : "";
        return id + "|" + s.getCount() + "|" + enchNbt;
    }

    // --- Imbue side panel model ---
    private enum Section { CURRENT, UNLOCKED, LOCKED }

    private static final class ImbueRow {
        Enchantment ench;       // null means: header row
        String label;           // "Efficiency III", "???", or header text
        Section section;        // null for headers
        int displayLevel;       // for UNLOCKED rows (max unlocked level)
        int yTop, yBottom;      // layout (filled each frame)
        boolean incompatible;   // shaded out / non-clickable
        boolean selected;       // chosen by user
    }

    // --- Client → Server: send current imbue selections ---
    private void sendImbueSelectionsToServer() {
        var buf = PacketByteBufs.create();
        buf.writeVarInt(imbueSelected.size());
        for (var e : imbueSelected.entrySet()) {
            Identifier id = Registries.ENCHANTMENT.getId(e.getKey());
            if (id == null) continue;
            buf.writeString(id.toString());
            buf.writeVarInt(Math.max(1, e.getValue()));
        }
        ClientPlayNetworking.send(ResearchTableMod.SET_IMBUE_SELECTIONS, buf);
    }

    private final List<ImbueRow> imbueRows = new ArrayList<>();
    private final Map<Enchantment,Integer> imbueSelected = new HashMap<>();
    private int imbueHoverIndex = -1;

    // level/cost tallies
    private int enchitemlevels = 0;
    private int newenchitemlevels = 0;
    private int enchlevelincrease = 0;
    private int levelcost = 0;
    private int lapiscost = 0;

    // --- Imbue slot nudge (tab 2) ---
    private static final int IMBUE_DX = 13;
    private static final int IMBUE_DY = -8;

    private boolean imbueShiftApplied = false;
    private int inputBaseX = Integer.MIN_VALUE;
    private int inputBaseY = Integer.MIN_VALUE;

    // Draw text with a solid black outline
    private void drawOutlinedText(DrawContext ctx, String s, int x, int y, int color) {
        int black = 0xFF000000;
        ctx.drawText(this.textRenderer, Text.literal(s), x - 1, y - 1, black, false);
        ctx.drawText(this.textRenderer, Text.literal(s), x,     y - 1, black, false);
        ctx.drawText(this.textRenderer, Text.literal(s), x + 1, y - 1, black, false);
        ctx.drawText(this.textRenderer, Text.literal(s), x - 1, y,     black, false);
        ctx.drawText(this.textRenderer, Text.literal(s), x + 1, y,     black, false);
        ctx.drawText(this.textRenderer, Text.literal(s), x - 1, y + 1, black, false);
        ctx.drawText(this.textRenderer, Text.literal(s), x,     y + 1, black, false);
        ctx.drawText(this.textRenderer, Text.literal(s), x + 1, y + 1, black, false);
        ctx.drawText(this.textRenderer, Text.literal(s), x, y, color, false);
    }

    // --- Fancy glint bookshelf icon ---
    private static final ItemStack GLINT_BOOKSHELF;
    static {
        GLINT_BOOKSHELF = new ItemStack(Items.CHISELED_BOOKSHELF);
        GLINT_BOOKSHELF.addEnchantment(Enchantments.UNBREAKING, 1);
        GLINT_BOOKSHELF.getOrCreateNbt().putInt("HideFlags", 1);
    }

    // ---------- ctor ----------
    public ResearchTableScreen(ResearchTableScreenHandler handler, PlayerInventory inv, Text title) {
        super(handler, inv, title);
        this.backgroundWidth = 254;
        this.backgroundHeight = 166;
    }

    private void drawImbueCostsMiddle(DrawContext ctx, int guiX, int guiY) {
        var mc = MinecraftClient.getInstance();
        int playerLevels = (mc != null && mc.player != null) ? mc.player.experienceLevel : 0;

        ItemStack lapis = handler.getSlot(ResearchTableScreenHandler.LAPIS_SLOT).getStack();
        int playerLapis = lapis.isEmpty() ? 0 : lapis.getCount();

        boolean hasEnoughLevels = playerLevels >= levelcost;
        boolean hasEnoughLapis  = playerLapis >= lapiscost;
        boolean allEnough       = hasEnoughLevels && hasEnoughLapis;

        int x = guiX + COST_AREA_X;
        int y = guiY + COST_AREA_Y;

        String header = "Imbuing cost:";
        int hw = this.textRenderer.getWidth(header);
        int hp = 1;
        int hx1 = x - hp;
        int hy1 = y - hp;
        int hx2 = x + hw + hp - 1;
        int hy2 = y + 8 + hp;

        ctx.fill(hx1, hy1, hx2, hy2, COL_HDR_BG);
        ctx.drawText(this.textRenderer, Text.literal(header), x, y, allEnough ? COL_OK : COL_BAD, false);
        y += 12;

        int expX = x + 20;
        int expY = y - 2;
        ctx.drawTexture(TEX_EXP, expX, expY, 0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);

        String lvlTxt = String.valueOf(levelcost);
        int lvlColor = hasEnoughLevels ? COL_OK : COL_BAD;
        drawOutlinedText(ctx, lvlTxt, expX + ICON_SIZE + 4, y, lvlColor);

        y += 14;
        ctx.drawItem(new ItemStack(Items.LAPIS_LAZULI), expX - 2, y - 4);
        String lapisTxt = String.valueOf(lapiscost);
        int lapColor = hasEnoughLapis ? COL_OK : COL_BAD;
        drawOutlinedText(ctx, lapisTxt, expX + ICON_SIZE + 4, y, lapColor);
    }

    // ---------- init / resize ----------
    @Override
    protected void init() {
        super.init();

        ClientPlayNetworking.send(ResearchTableMod.REQUEST_SYNC_PACKET, PacketByteBufs.empty());

        handler.setImbueMode(false);
        sendImbueMode(false);

        int guiX = (this.width - this.backgroundWidth - 2) / 2;
        int guiY = (this.height - this.backgroundHeight) / 2;

        consumeBtn = ButtonWidget.builder(
                Text.translatable("screen.researchtable.consume"),
                b -> ClientPlayNetworking.send(ResearchTableMod.CONSUME_INPUT_PACKET, PacketByteBufs.empty())
        ).dimensions(guiX + 54, guiY + 54, 60, 20).build();
        addDrawableChild(consumeBtn);

        updateButtons();
        applyImbueShiftIfNeeded();
        ItemStack s = handler.getSlot(ResearchTableScreenHandler.INPUT_SLOT).getStack();
        lastInputSig = inputSignature(s);
    }

    @Override
    public void resize(MinecraftClient client, int width, int height) {
        super.resize(client, width, height);
        this.tab = 0;
        updateButtons();
        applyImbueShiftIfNeeded();
    }

    private void updateButtons() {
        if (consumeBtn != null) consumeBtn.visible = (tab == 1);
    }

    // ---------- server sync helper ----------
    private void sendImbueMode(boolean on) {
        var buf = PacketByteBufs.create();
        buf.writeBoolean(on);
        ClientPlayNetworking.send(ResearchTableMod.SET_IMBUE_MODE, buf);
    }

    // ---------- draw ----------
    @Override
    protected void drawForeground(DrawContext ctx, int mouseX, int mouseY) {}

    @Override
    protected void drawMouseoverTooltip(DrawContext ctx, int x, int y) {
        if (tab == 0) return;
        super.drawMouseoverTooltip(ctx, x, y);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        applyImbueShiftIfNeeded();
        this.renderBackground(ctx);
        if (tab == 0) {
            this.drawBackground(ctx, delta, mouseX, mouseY);
        } else {
            super.render(ctx, mouseX, mouseY, delta);
            this.drawMouseoverTooltip(ctx, mouseX, mouseY);
        }
    }

    @Override
    protected void drawBackground(DrawContext ctx, float delta, int mouseX, int mouseY) {
        final int x = this.x;
        final int y = this.y;

        Identifier bg = switch (tab) {
            case 0 -> BG_ENCHANTS;
            case 1 -> BG_RESEARCH;
            default -> BG_IMBUING;
        };

        ctx.drawTexture(bg, x, y, 0, 0, this.backgroundWidth, this.backgroundHeight, 256, 256);

        int headerX = x + CONTENT_PAD_LEFT + 4;
        int headerY = y + 8;
        Text header = Text.literal(
                Text.translatable("screen.researchtable.title").getString() + " – " +
                        switch (tab) {
                            case 0 -> Text.translatable("screen.researchtable.tab.enchants").getString();
                            case 1 -> Text.translatable("screen.researchtable.tab.research").getString();
                            default -> Text.translatable("screen.researchtable.tab.apply").getString();
                        }
        );
        ctx.drawText(this.textRenderer, header, headerX, headerY, COL_HEADER_TEXT, false);

        int iconX = x + 17;
        ctx.drawItem(GLINT_BOOKSHELF, iconX, y + 15);
        ctx.drawItem(new ItemStack(Items.KNOWLEDGE_BOOK), iconX, y + 55);
        ctx.drawItem(new ItemStack(Items.ANVIL), iconX, y + 95);

        if (tab == 0) {
            drawEnchantPane(ctx, x, y, mouseX, mouseY);
        } else if (tab == 1) {
            drawResearchPreview(ctx, x, y, mouseX, mouseY);
        } else if (tab == 2) {
            ItemStack item = handler.getSlot(ResearchTableScreenHandler.INPUT_SLOT).getStack();
            boolean showSide = !item.isEmpty() && (item.isEnchantable() || !EnchantmentHelper.get(item).isEmpty());
            if (showSide) {
                int guiX = (this.width - this.backgroundWidth) / 2;
                int guiY = (this.height - this.backgroundHeight) / 2;
                int sideX = guiX + this.backgroundWidth - RIGHT_PANEL_OVERLAP;
                int sideY = guiY;

                ctx.drawTexture(BG_IMBUE_SIDE, sideX, sideY, 0, 0, RIGHT_PANEL_DRAW_W, this.backgroundHeight, 256, 256);
                drawImbueSidePanel(ctx, guiX, guiY, mouseX, mouseY);
                drawImbueCostsMiddle(ctx, x, y);
            }
            if (!showSide && !imbueSelected.isEmpty()) {
                imbueSelected.clear();
                sendImbueSelectionsToServer();
            }
        }
    }

    // ---------- Enchants tab ----------
    private void drawEnchantPane(DrawContext ctx, int guiX, int guiY, int mouseX, int mouseY) {
        int cx = guiX + CONTENT_PAD_LEFT;
        int cy = guiY + CONTENT_PAD_TOP;
        int cw = this.backgroundWidth - CONTENT_PAD_LEFT - CONTENT_PAD_RIGHT;
        int ch = this.backgroundHeight - CONTENT_PAD_TOP - CONTENT_PAD_BOTTOM;

        int listXb = cx;
        int listYb = cy;
        int listWb = LIST_W;
        int listHb = ch;

        int panelX = cx + LIST_W + PANEL_GAP + PANEL_OFFSET_X;
        int panelY = cy;
        int panelW = cw - LIST_W - PANEL_GAP;
        int panelH = ch;

        record Row(String id, Enchantment ench, String title, String subtitle, int color) {}
        Map<String, Integer> prog = ResearchClientState.progress();
        var unlocked = ResearchClientState.unlocked();

        List<Row> rows = new ArrayList<>();
        for (Enchantment ench : Registries.ENCHANTMENT) {
            if (ResearchTableMod.isHiddenEnch(ench)) continue;
            Identifier id = Registries.ENCHANTMENT.getId(ench);
            if (id == null) continue;
            String idStr = id.toString();
            boolean isUnlocked = unlocked.contains(idStr);
            int total = prog.getOrDefault(idStr, 0);

            if (!isUnlocked) {
                rows.add(new Row(idStr, ench, "???", null, COLOR_LOCKED));
            } else {
                int usable = ResearchPersistentState.usableLevelFor(total);
                int capped = Math.min(usable, ench.getMaxLevel());
                String name = Text.translatable(ench.getTranslationKey()).getString();
                if (capped == 0) {
                    String subtitle = "Level I Locked";
                    rows.add(new Row(idStr, ench, name, subtitle, COLOR_LOCKED));
                } else {
                    String subtitle = "- Level " + toRoman(capped) + " Unlocked";
                    rows.add(new Row(idStr, ench, name, subtitle, COLOR_UNLOCKED));
                }
            }
        }

        int listX = listXb + 4;
        int listY = listYb + 4;
        int visible = Math.max(1, (listHb - 8) / ROW_H);

        if (scroll < 0) scroll = 0;
        if (scroll > Math.max(0, rows.size() - visible)) scroll = Math.max(0, rows.size() - visible);

        hoveredRow = -1;
        if (mouseX >= listX && mouseX <= listX + listWb - 8 && mouseY >= listY && mouseY < listY + visible * ROW_H) {
            int idx = (mouseY - listY) / ROW_H;
            int actual = scroll + idx;
            if (actual >= 0 && actual < rows.size()) hoveredRow = actual;
        }

        int end = Math.min(rows.size(), scroll + visible);
        int textMax = listWb - 10;

        for (int i = scroll; i < end; i++) {
            int dy = listY + (i - scroll) * ROW_H;

            if (i == hoveredRow) {
                ctx.fill(listX, dy - 2, listX + listWb - 8, dy + ROW_H - 3, COLOR_HOVER);
            }

            Row row = rows.get(i);
            String clippedTitle = clipToWidth(row.title(), textMax);
            ctx.drawText(this.textRenderer, Text.literal(clippedTitle), listX + 2, dy + 1, row.color(), false);

            if (row.subtitle() != null && !row.subtitle().isEmpty()) {
                String clippedSub = clipToWidth(row.subtitle(), textMax);
                int subColor = (row.color() == COLOR_UNLOCKED) ? 0xFF9ED68F : COLOR_LOCKED;
                ctx.drawText(this.textRenderer, Text.literal(clippedSub), listX + 2, dy + 10, subColor, false);
            }

            int lineY = dy + ROW_H - 3;
            ctx.fill(listX, lineY, listX + listWb - 8, lineY + 1, 0xFF302B22);
            ctx.fill(listX, lineY - 22, listX + listWb - 8, lineY - 21, 0xFF27221B);
        }

        if (rows.size() > visible) {
            int barX = listXb + listWb - 7;
            int barY = listYb + 4;
            int barH = listHb - 8;
            int thumbH = Math.max(6, (int) (barH * (visible / (float) rows.size())));
            int thumbY = barY + (int) ((barH - thumbH) * (scroll / (float) (rows.size() - visible)));
            ctx.drawText(this.textRenderer, Text.literal("||"), barX, thumbY, 0x606060, false);
        }

        drawEnchantInfoPanel(ctx, panelX, panelY, panelW, panelH);
    }

    private void drawEnchantInfoPanel(DrawContext ctx, int panelX, int panelY, int panelW, int panelH) {
        if (selectedEnchId == null) {
            final int padX = 6;
            final int contentLeft   = panelX + padX;
            final int contentRight  = panelX + panelW - padX + 7;
            final int contentTop    = panelY + 6;
            final int contentBottom = panelY + panelH - 6;
            final int contentWidth  = contentRight - contentLeft;

            int y = contentTop - panelScroll;

            List<String> hintLines = wrapPlain(
                    Text.translatable("screen.researchtable.enchant_info_hint").getString(),
                    contentWidth
            );

            ctx.enableScissor(contentLeft, contentTop, contentRight, contentBottom);
            for (String line : hintLines) {
                ctx.drawText(this.textRenderer, Text.literal(line), contentLeft, y, COL_TEXT, false);
                y += 10;
            }
            ctx.disableScissor();

            panelContentHeight = y - contentTop;
            int visibleH = contentBottom - contentTop;
            int maxScroll = Math.max(0, panelContentHeight - visibleH);
            if (panelScroll > maxScroll) panelScroll = maxScroll;
            if (panelScroll < 0) panelScroll = 0;

            if (panelContentHeight > visibleH) {
                int barX = panelX + panelW - 5;
                int barY = contentTop;
                int barH = visibleH;
                int thumbH = Math.max(6, (int) (barH * (visibleH / (float) panelContentHeight)));
                int thumbY = barY + (int) ((barH - thumbH) * (panelScroll / (float) (panelContentHeight - visibleH)));
                ctx.drawText(this.textRenderer, Text.literal("||"), barX, thumbY, 0x606060, false);
            }
            return;
        }

        var unlockedSet = ResearchClientState.unlocked();
        if (!unlockedSet.contains(selectedEnchId)) {
            final int padX = 6;
            final int contentLeft   = panelX + padX;
            final int contentRight  = panelX + panelW - padX;
            final int contentTop    = panelY + 6;
            final int contentBottom = panelY + panelH - 6;
            final int contentWidth  = contentRight - contentLeft;

            int y = contentTop - panelScroll;

            ctx.enableScissor(contentLeft, contentTop, contentRight, contentBottom);
            ctx.drawText(this.textRenderer, Text.literal("???"), contentLeft, y, 0xFF9AA0A6, false);
            y += 12;

            List<String> hintLines = wrapPlain(
                    Text.translatable("screen.researchtable.undiscovered_hint").getString(),
                    contentWidth
            );
            for (String line : hintLines) {
                ctx.drawText(this.textRenderer, Text.literal(line), contentLeft, y, 0xFFB0B6BB, false);
                y += 10;
            }
            ctx.disableScissor();

            panelContentHeight = y - contentTop;
            int visibleH = contentBottom - contentTop;
            int maxScroll = Math.max(0, panelContentHeight - visibleH);
            if (panelScroll > maxScroll) panelScroll = maxScroll;
            if (panelScroll < 0) panelScroll = 0;

            if (panelContentHeight > visibleH) {
                int barX = panelX + panelW - 5;
                int barY = contentTop;
                int barH = visibleH;
                int thumbH = Math.max(6, (int) (barH * (visibleH / (float) panelContentHeight)));
                int thumbY = barY + (int) ((barH - thumbH) * (panelScroll / (float) (panelContentHeight - visibleH)));
                ctx.drawText(this.textRenderer, Text.literal("||"), barX, thumbY, 0x606060, false);
            }
            return;
        }

        Enchantment ench = this.selectedEnch;
        if (ench == null) {
            ench = Registries.ENCHANTMENT.get(Identifier.tryParse(selectedEnchId));
            this.selectedEnch = ench;
        }
        if (ench == null) {
            ctx.drawText(this.textRenderer, Text.literal(selectedEnchId), panelX + 6, panelY + 6, COL_TEXT, false);
            panelContentHeight = 0;
            panelScroll = 0;
            return;
        }

        final int padX = 6;
        final int contentLeft   = panelX + padX;
        final int contentRight  = panelX + panelW - padX;
        final int contentTop    = panelY + 6;
        final int contentBottom = panelY + panelH - 6;
        final int contentWidth  = contentRight - contentLeft;

        int y = contentTop - panelScroll;

        ctx.enableScissor(contentLeft, contentTop, contentRight, contentBottom);

        String baseName = Text.translatable(ench.getTranslationKey()).getString();
        drawScrollingText(ctx, baseName, contentLeft, y, contentWidth, 0xFFFFFFFF);
        y += 12;

        Identifier enchId = Registries.ENCHANTMENT.getId(ench);
        String desc = ClientEnchantDescriptions.get(enchId.toString());
        if (desc == null || desc.isEmpty() || "???".equals(desc)) desc = "No description available.";

        List<String> lines = wrapPlain(desc, contentWidth);
        for (String l : lines) {
            ctx.drawText(this.textRenderer, Text.literal(l), contentLeft, y, 0xFFCFCFCF, false);
            y += 10;
        }
        y += 6;

        int totalPts = ResearchClientState.progress().getOrDefault(enchId.toString(), 0);
        int usable = ResearchPersistentState.usableLevelFor(totalPts);
        int maxLevel = ench.getMaxLevel();

        if (usable >= maxLevel) {
            ctx.drawText(this.textRenderer, Text.translatable("screen.researchtable.research_complete"),
                    contentLeft, y, COLOR_COMPLETE, false);
            y += 12;
        } else {
            int nextLevel = Math.min(usable + 1, maxLevel);
            int nextNeeded = ResearchPersistentState.pointsForLevel(nextLevel);
            ctx.drawText(this.textRenderer, Text.translatable("screen.researchtable.researching"),
                    contentLeft, y, 0xFFFFFFFF, false);
            y += 12;
            ctx.drawText(this.textRenderer, Text.literal("Level " + toRoman(nextLevel)),
                    contentLeft, y, COL_TEXT, false);
            y += 12;
            ctx.drawText(this.textRenderer, Text.literal(totalPts + " / " + nextNeeded),
                    contentLeft, y, COL_TEXT, false);
            y += 12;
        }

        ctx.drawText(this.textRenderer,
                Text.translatable("screen.researchtable.max_level", toRoman(maxLevel)),
                contentLeft, y, COLOR_COMPLETE, false);
        y += 12;

        List<ItemStack> appl = getApplicableIcons(ench);
        final int cols = APPLICABLE_COLS;
        int maxIcons = Math.min(appl.size(), cols * 2);
        for (int i = 0; i < maxIcons; i++) {
            int col = i % cols;
            int row = i / cols;
            ctx.drawItem(appl.get(i), contentLeft + col * 18, y + row * 18);
        }
        int applRows = (int) Math.ceil(maxIcons / (double) cols);
        y += applRows * 18;
        y += 6;

        ctx.drawText(this.textRenderer, Text.translatable("screen.researchtable.research_items"),
                contentLeft, y, COL_TEXT, false);
        y += 12;

        var mats = getResearchMaterialsFor(enchId.toString());
        int mx = contentLeft;
        int my = y;
        int show = Math.min(mats.size(), 4);
        for (int i = 0; i < show; i++) {
            var me = mats.get(i);
            ctx.drawItem(me.stack, mx + i * 18, my);
            String p = "+" + me.points;
            int tx = mx + i * 18 + 18 + 2;
            int ty = my + 4;
            ctx.drawText(this.textRenderer, Text.literal(p), tx, ty, COL_TEXT, false);
        }
        y += 18;

        ctx.drawText(this.textRenderer, Text.literal(" "), contentLeft, y, 0x00000000, false);
        y += 10;
        ctx.drawText(this.textRenderer, Text.literal(" "), contentLeft, y, 0x00000000, false);
        y += 10;

        ctx.disableScissor();

        panelContentHeight = y - contentTop;

        int visibleH = contentBottom - contentTop;
        int maxScroll2 = Math.max(0, panelContentHeight - visibleH);
        if (panelScroll > maxScroll2) panelScroll = maxScroll2;
        if (panelScroll < 0) panelScroll = 0;

        if (panelContentHeight > visibleH) {
            int barX = panelX + (contentRight - contentLeft) + padX;
            int barY = contentTop;
            int barH = visibleH;
            int thumbH = Math.max(6, (int) (barH * (visibleH / (float) panelContentHeight)));
            int thumbY = barY + (int) ((barH - thumbH) * (panelScroll / (float) (panelContentHeight - visibleH)));
            ctx.drawText(this.textRenderer, Text.literal("||"), barX, thumbY, 0x606060, false);
        }
    }

    // ---------- Research tab ----------
    private void drawResearchPreview(DrawContext ctx, int guiX, int guiY, int mouseX, int mouseY) {
        final int winX = guiX + R_WIN_X_OFFSET;
        final int winY = guiY + R_WIN_Y_OFFSET;
        final int winW = R_WIN_W;
        final int winH = R_WIN_H;

        final int cx = winX + R_PREVIEW_PAD;
        final int cy = winY + R_PREVIEW_PAD;
        final int cw = winW - R_PREVIEW_PAD * 2;
        final int ch = winH - R_PREVIEW_PAD * 2;

        List<String> rows = new ArrayList<>();

        ItemStack stack = handler.getSlot(ResearchTableScreenHandler.INPUT_SLOT).getStack();
        if (!stack.isEmpty()) {
            boolean showNotDiscovered = false;

            // a) research material path (datapack-driven)
            if (isResearchMaterial(stack)) {
                Identifier itemId = Registries.ITEM.getId(stack.getItem());
                Map<String, Integer> enchMap = (itemId != null) ? ResearchItems.entries().get(itemId.toString()) : null;

                if (enchMap != null && !enchMap.isEmpty()) {
                    // Only show enchants the player has discovered (progress > 0), matching server Case B
                    var prog = ResearchClientState.progress();
                    int count = stack.getCount();

                    var discovered = enchMap.entrySet().stream()
                            .filter(e -> prog.getOrDefault(e.getKey(), 0) > 0)
                            .toList();

                    if (discovered.isEmpty()) {
                        showNotDiscovered = true;
                    } else {
                        for (var e : discovered) {
                            String enchIdStr = e.getKey();
                            int ptsPer = Math.max(1, e.getValue());
                            int points = ptsPer * count;

                            Enchantment en = Registries.ENCHANTMENT.get(Identifier.tryParse(enchIdStr));
                            String name = (en != null)
                                    ? Text.translatable(en.getTranslationKey()).getString()
                                    : enchIdStr;
                            rows.add(name + " +" + points);
                        }
                    }
                } else {
                    // No mapping for this item: nothing to show
                    showNotDiscovered = true;
                }
            }

            if (showNotDiscovered) {
                rows.add(Text.translatable("screen.researchtable.not_discovered").getString());
            } else if (rows.isEmpty()) {
                // b) enchanted items/books (discovery path)
                Map<Enchantment, Integer> ench = EnchantmentHelper.get(stack);
                if (!ench.isEmpty()) {
                    for (var e : ench.entrySet()) {
                        if (ResearchTableMod.isHiddenEnch(e.getKey())) continue;
                        Enchantment en = e.getKey();
                        int level = Math.max(1, e.getValue());
                        String name = Text.translatable(en.getTranslationKey()).getString();
                        int points = level * RESEARCH_POINTS_PER_LEVEL;
                        rows.add(name + " +" + points);
                    }
                }
            }
        }

        researchContentHeight = rows.size() * RESEARCH_LINE_H;

        int maxScroll = Math.max(0, researchContentHeight - ch);
        if (researchScroll < 0) researchScroll = 0;
        if (researchScroll > maxScroll) researchScroll = maxScroll;

        ctx.enableScissor(cx, cy, cx + cw, cy + ch);
        int y = cy - researchScroll;
        for (String line : rows) {
            drawScrollingText(ctx, line, cx, y, cw, COL_TEXT);
            y += RESEARCH_LINE_H;
        }
        ctx.disableScissor();

        if (researchContentHeight > ch) {
            int barX = winX + winW - 5;
            int barY = winY + R_PREVIEW_PAD;
            int barH = ch;
            int thumbH = Math.max(6, (int) (barH * (ch / (float) researchContentHeight)));
            int thumbY = barY + (int) ((barH - thumbH) * (researchScroll / (float) (researchContentHeight - ch)));
            ctx.drawText(this.textRenderer, Text.literal("||"), barX, thumbY, 0x606060, false);
        }
    }

    private boolean isResearchMaterial(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Identifier id = Registries.ITEM.getId(stack.getItem());
        if (id == null) return false;
        return ResearchItems.entries().containsKey(id.toString());
    }

    // ---------- Imbue side panel ----------
    private void drawImbueSidePanel(DrawContext ctx, int guiX, int guiY, int mouseX, int mouseY) {
        final int pad = 5;
        int sideX = guiX + this.backgroundWidth - RIGHT_PANEL_OVERLAP;
        int listLeft   = sideX + pad - 1;
        int listTop    = guiY + 16;
        int listRight  = sideX + RIGHT_PANEL_DRAW_W - pad - 2;
        int listBottom = guiY + this.backgroundHeight - pad - 13;

        ItemStack stack = handler.getSlot(ResearchTableScreenHandler.INPUT_SLOT).getStack();
        if (stack.isEmpty()) return;

        buildImbueRows(stack);

        int totalH = 0;
        for (ImbueRow r : imbueRows) {
            totalH += (r.ench == null) ? 13 : 10;
        }
        imbueContentHeight = totalH;

        int visibleH = listBottom - listTop;
        int maxScroll = Math.max(0, imbueContentHeight - visibleH);
        if (imbuePanelScroll > maxScroll) imbuePanelScroll = maxScroll;
        if (imbuePanelScroll < 0) imbuePanelScroll = 0;

        imbueHoverIndex = -1;
        int y = listTop - imbuePanelScroll;

        ctx.enableScissor(listLeft, listTop, listRight, listBottom);
        for (int i = 0; i < imbueRows.size(); i++) {
            ImbueRow r = imbueRows.get(i);
            int rowH = (r.ench == null) ? 13 : 10;

            r.yTop = y;
            r.yBottom = y + rowH - 1;

            if (r.yBottom >= listTop && r.yTop <= listBottom) {
                if (r.ench == null) {
                    int underlineY = y - 1;
                    ctx.fill(listLeft, y, listRight, y + rowH - 2, ROW_BG_HEADER);
                    ctx.fill(listLeft, underlineY, listRight, underlineY + 1, ROW_BG_INCOMPAT);
                    ctx.drawText(this.textRenderer, Text.literal(r.label), listLeft + 1, y + 2, 0xFFD4D8DD, true);
                    y += 12;
                    continue;
                }

                boolean withinX = mouseX >= listLeft && mouseX <= listRight;
                boolean withinY = mouseY >= r.yTop - 1 && mouseY < r.yBottom;

                boolean isSelected = imbueSelected.containsKey(r.ench);
                int textcol_if_incompat = (r.incompatible) ? COLOR_LOCKED : COL_TEXT;

                ctx.fill(listLeft, y - 1, listRight, y + rowH - 1, ROW_BG_BASE);
                if (isSelected) {
                    ctx.fill(listLeft, y - 1, listRight, y + rowH - 1, ROW_BG_SELECTED);
                } else if (r.incompatible) {
                    ctx.fill(listLeft, y - 1, listRight, y + rowH - 1, ROW_BG_INCOMPAT);
                } else if (r.section != Section.CURRENT && r.section != Section.LOCKED && withinX && withinY) {
                    ctx.fill(listLeft, y - 1, listRight, y + rowH - 1, ROW_BG_HOVER);
                    imbueHoverIndex = i;
                }

                ctx.drawText(this.textRenderer, Text.literal(r.label), listLeft + 2, y, textcol_if_incompat, false);
            }

            y += rowH;
        }
        ctx.disableScissor();

        if (imbueContentHeight > visibleH) {
            int barX = listRight - 2;
            int barY = listTop;
            int barH = visibleH;
            int thumbH = Math.max(6, (int)(barH * (visibleH / (float)imbueContentHeight)));
            int thumbY = barY + (int)((barH - thumbH) * (imbuePanelScroll / (float)(imbueContentHeight - visibleH)));
            ctx.drawText(this.textRenderer, Text.literal("||"), barX, thumbY, 0x606060, false);
        }
    }

    private void buildImbueRows(ItemStack stack) {
        imbueRows.clear();

        Map<Enchantment, Integer> current = EnchantmentHelper.get(stack);
        enchitemlevels = current.values().stream().mapToInt(Integer::intValue).sum();

        var unlockedIds = ResearchClientState.unlocked(); // Set<String>
        var prog = ResearchClientState.progress();        // Map<String, Integer>

        Function<Enchantment, Integer> maxUnlockedLevel = (en) -> {
            Identifier id = Registries.ENCHANTMENT.getId(en);
            if (id == null) return 0;
            int total = prog.getOrDefault(id.toString(), 0);
            int usable = ResearchPersistentState.usableLevelFor(total);
            return Math.min(usable, en.getMaxLevel());
        };

        List<Enchantment> applicable = new ArrayList<>();
        for (Enchantment en : Registries.ENCHANTMENT) {
            try { if (en.isAcceptableItem(stack)) applicable.add(en); } catch (Throwable ignored) {}
        }

        int y = 0;

        addHeaderRow("Current");
        for (var e : current.entrySet()) {
            if (ResearchTableMod.isHiddenEnch(e.getKey())) continue;
            var r = new ImbueRow();
            r.ench = e.getKey();
            r.displayLevel = e.getValue();
            r.section = Section.CURRENT;
            r.label = Text.translatable(r.ench.getTranslationKey()).getString() + " " + toRoman(r.displayLevel);
            r.incompatible = false;
            r.selected = false;
            imbueRows.add(r);
        }
        y += 4;

        List<Enchantment> unlockedList = new ArrayList<>();
        for (Enchantment en : applicable) {
            if (current.containsKey(en)) continue;
            Identifier id = Registries.ENCHANTMENT.getId(en);
            if (id == null) continue;
            if (unlockedIds.contains(id.toString())) {
                int maxL = maxUnlockedLevel.apply(en);
                if (maxL > 0) unlockedList.add(en);
            }
        }

        addHeaderRow("Unlocked");
        List<Enchantment> lockedList = new ArrayList<>();

        for (Enchantment en : applicable) {
            if (ResearchTableMod.isHiddenEnch(en)) continue;

            Identifier id = Registries.ENCHANTMENT.getId(en);
            if (id == null) continue;

            int currentLevel = current.getOrDefault(en, 0);
            int maxUnlocked = maxUnlockedLevel.apply(en);

            if (maxUnlocked > currentLevel) {
                // SHOW as selectable upgrade to the best unlocked level
                ImbueRow r = new ImbueRow();
                r.ench = en;
                r.displayLevel = maxUnlocked; // target is absolute level, not delta
                r.section = Section.UNLOCKED;
                r.label = Text.translatable(en.getTranslationKey()).getString() + " " + toRoman(maxUnlocked);
                r.incompatible = false;
                r.selected = false;
                imbueRows.add(r);
            } else {
                // Not upgradeable (either locked or already at/beyond unlocked)
                lockedList.add(en);
            }
        }

// Locked section shows applicable but not yet unlocked (or already at cap vs research)
        addHeaderRow("Locked");
        for (Enchantment en : lockedList) {
            if (current.containsKey(en)) {
                // Already on the item but you don't have a higher unlocked level
                // Keep it locked here as "???"
            }
            ImbueRow r = new ImbueRow();
            r.ench = en;
            r.displayLevel = 0;
            r.section = Section.LOCKED;
            r.label = "???";
            r.incompatible = false;
            r.selected = false;
            imbueRows.add(r);
        }

        flagIncompatibles(current.keySet());
        computeCosts();

        for (ImbueRow r : imbueRows) {
            if (r.ench != null) {
                r.selected = imbueSelected.containsKey(r.ench);
            }
        }
    }

    private void addHeaderRow(String title) {
        var hdr = new ImbueRow();
        hdr.ench = null;
        hdr.label = title;
        hdr.section = null;
        hdr.displayLevel = 0;
        hdr.incompatible = false;
        hdr.selected = false;
        imbueRows.add(hdr);
    }

    private void flagIncompatibles(Set<Enchantment> currentOnItem) {
        Set<Enchantment> selectedSet = imbueSelected.keySet();

        for (ImbueRow r : imbueRows) {
            if (r.ench == null) continue;
            if (r.section == Section.CURRENT || r.section == Section.LOCKED) continue;

            boolean conflict = false;

            for (Enchantment cur : currentOnItem) {
                if (cur == r.ench) continue;
                if (!areCompatible(r.ench, cur)) { conflict = true; break; }
            }

            if (!conflict) {
                for (Enchantment sel : selectedSet) {
                    if (sel == r.ench) continue;
                    if (!areCompatible(r.ench, sel)) { conflict = true; break; }
                }
            }

            r.incompatible = conflict;
        }
    }

    private static boolean areCompatible(net.minecraft.enchantment.Enchantment a,
                                         net.minecraft.enchantment.Enchantment b) {
        if (a == b) return true;
        try {
            var method = net.minecraft.enchantment.Enchantment.class
                    .getDeclaredMethod("canAccept", net.minecraft.enchantment.Enchantment.class);
            method.setAccessible(true);

            boolean ab = (boolean) method.invoke(a, b);
            boolean ba = (boolean) method.invoke(b, a);
            return ab && ba;
        } catch (NoSuchMethodException e) {
            try {
                var method = net.minecraft.enchantment.Enchantment.class
                        .getDeclaredMethod("isCompatibleWith", net.minecraft.enchantment.Enchantment.class);
                method.setAccessible(true);
                boolean ab = (boolean) method.invoke(a, b);
                boolean ba = (boolean) method.invoke(b, a);
                return ab && ba;
            } catch (Throwable inner) {
                return true;
            }
        } catch (Throwable t) {
            return true;
        }
    }

    private void computeCosts() {
        Map<Enchantment, Integer> current = imbueRows.stream()
                .filter(r -> r.section == Section.CURRENT && r.ench != null)
                .collect(java.util.stream.Collectors.toMap(r -> r.ench, r -> r.displayLevel, Math::max));

        int increase = 0;
        for (var e : imbueSelected.entrySet()) {
            int cur = current.getOrDefault(e.getKey(), 0);
            int target = Math.max(1, e.getValue());
            increase += Math.max(0, target - cur);
        }

        enchitemlevels = current.values().stream().mapToInt(Integer::intValue).sum();
        newenchitemlevels = enchitemlevels + increase;
        enchlevelincrease = increase;

        if (enchlevelincrease == 0) {
            levelcost = 0;
            lapiscost = 0;
            return;
        }

        double term1 = Math.pow(enchitemlevels, 1.45);
        double term2 = Math.pow(10.0 * enchlevelincrease, 0.8);
        levelcost = (int)Math.ceil(term1 + term2);

        double l1 = Math.pow(enchitemlevels, 1.5);
        double l2 = Math.pow(enchlevelincrease, 1.5);
        lapiscost = Math.min(64, (int)Math.ceil(l1 + l2));
    }

    // ---------- Slot XY shifter via accessor ----------
    private void applyImbueShiftIfNeeded() {
        var slot = this.handler.getSlot(ResearchTableScreenHandler.INPUT_SLOT);
        var xy = (com.jamsackman.researchtable.access.SlotXY)(Object) slot;

        if (inputBaseX == Integer.MIN_VALUE) {
            inputBaseX = xy.researchtable$getX();
            inputBaseY = xy.researchtable$getY();
        }

        if (tab == 2 && !imbueShiftApplied) {
            xy.researchtable$setX(inputBaseX + IMBUE_DX);
            xy.researchtable$setY(inputBaseY + IMBUE_DY);
            imbueShiftApplied = true;
        } else if (tab != 2 && imbueShiftApplied) {
            xy.researchtable$setX(inputBaseX);
            xy.researchtable$setY(inputBaseY);
            imbueShiftApplied = false;
        }
    }

    // ---------- Input ----------
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        final int guiX = (this.width - this.backgroundWidth) / 2;
        final int guiY = (this.height - this.backgroundHeight) / 2;

        if (tab == 0) {
            final int cx = guiX + CONTENT_PAD_LEFT;
            final int cy = guiY + CONTENT_PAD_TOP;
            final int cw = this.backgroundWidth - CONTENT_PAD_LEFT - CONTENT_PAD_RIGHT;
            final int ch = this.backgroundHeight - CONTENT_PAD_TOP - CONTENT_PAD_BOTTOM;

            final int listX = cx + 4;
            final int listY = cy + 4;
            final int listW = LIST_W - 8;
            final int listH = ch - 8;

            final int panelX = cx + LIST_W + PANEL_GAP + PANEL_OFFSET_X;
            final int panelY = cy;
            final int panelW = cw - LIST_W - PANEL_GAP;
            final int panelH = ch;

            final int listStep  = (amount > 0) ? -1  : 1;
            final int panelStep = (amount > 0) ? -12 : 12;

            if (mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= panelY && mouseY <= panelY + panelH) {
                int contentTop    = panelY + 6;
                int contentBottom = panelY + panelH - 6;
                int visibleH      = contentBottom - contentTop;

                int maxScroll = Math.max(0, panelContentHeight - visibleH);
                panelScroll = Math.max(0, Math.min(maxScroll, panelScroll + panelStep));
                return true;
            }

            if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
                scroll += listStep;
                if (scroll < 0) scroll = 0;
                return true;
            }
            return false;
        }

        if (tab == 1) {
            final int winX = guiX + R_WIN_X_OFFSET;
            final int winY = guiY + R_WIN_Y_OFFSET;
            final int winW = R_WIN_W;
            final int winH = R_WIN_H;

            if (mouseX >= winX && mouseX <= winX + winW && mouseY >= winY && mouseY <= winY + winH) {
                int visible = winH - R_PREVIEW_PAD * 2;
                int maxScroll = Math.max(0, researchContentHeight - visible);
                int step = (amount > 0) ? -12 : 12;
                researchScroll = Math.max(0, Math.min(maxScroll, researchScroll + step));
                return true;
            }
            return false;
        }

        if (tab == 2) {
            final int pad = 5;
            int sideX = (this.width - this.backgroundWidth) / 2 + this.backgroundWidth - RIGHT_PANEL_OVERLAP;
            int listLeft   = sideX + pad - 1;
            int listTop    = (this.height - this.backgroundHeight) / 2 + 16;
            int listRight  = sideX + RIGHT_PANEL_DRAW_W - pad - 2;
            int listBottom = (this.height - this.backgroundHeight) / 2 + this.backgroundHeight - pad - 13;

            if (mouseX >= listLeft && mouseX <= listRight && mouseY >= listTop && mouseY <= listBottom) {
                int visibleH = listBottom - listTop;
                int maxScroll = Math.max(0, imbueContentHeight - visibleH);
                int step = (amount > 0) ? -12 : 12;

                imbuePanelScroll = Math.max(0, Math.min(maxScroll, imbuePanelScroll + step));
                return true;
            }
        }

        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int guiX = (this.width - this.backgroundWidth) / 2;
        int guiY = (this.height - this.backgroundHeight) / 2;

        int tabAbsX = guiX + TAB_X1;
        int tabW    = (TAB_X2 - TAB_X1);

        if (mouseX >= tabAbsX && mouseX <= tabAbsX + tabW && mouseY >= guiY + TAB0_Y1 && mouseY <= guiY + TAB0_Y2) {
            tab = 0;
            handler.setImbueMode(false);
            sendImbueMode(false);
            applyImbueShiftIfNeeded();
            ClientPlayNetworking.send(ResearchTableMod.REQUEST_SYNC_PACKET, PacketByteBufs.empty());
            updateButtons();
            playClickSound();
            return true;
        }
        if (mouseX >= tabAbsX && mouseX <= tabAbsX + tabW && mouseY >= guiY + TAB1_Y1 && mouseY <= guiY + TAB1_Y2) {
            tab = 1;
            handler.setImbueMode(false);
            sendImbueMode(false);
            applyImbueShiftIfNeeded();
            updateButtons();
            playClickSound();
            return true;
        }
        if (mouseX >= tabAbsX && mouseX <= tabAbsX + tabW && mouseY >= guiY + TAB2_Y1 && mouseY <= guiY + TAB2_Y2) {
            tab = 2;
            handler.setImbueMode(true);
            sendImbueMode(true);
            applyImbueShiftIfNeeded();
            updateButtons();
            playClickSound();
            sendImbueSelectionsToServer();
            return true;
        }

        if (tab == 0 && button == 0) {
            int cx = guiX + CONTENT_PAD_LEFT;
            int cy = guiY + CONTENT_PAD_TOP;
            int listX = cx + 4;
            int listY = cy + 4;
            int listWb = LIST_W;
            int listHb = this.backgroundHeight - CONTENT_PAD_TOP - CONTENT_PAD_BOTTOM;

            if (mouseX >= listX && mouseX <= listX + listWb - 8 && mouseY >= listY && mouseY < listY + listHb - 8) {
                if (hoveredRow >= 0) {
                    List<String> ids = new ArrayList<>();
                    for (Enchantment ench : Registries.ENCHANTMENT) {
                        Identifier id = Registries.ENCHANTMENT.getId(ench);
                        if (id != null) ids.add(id.toString());
                    }
                    if (hoveredRow < ids.size()) {
                        selectedEnchId = ids.get(hoveredRow);
                        selectedEnch = Registries.ENCHANTMENT.get(Identifier.tryParse(selectedEnchId));
                        panelScroll = 0;
                        playClickSound();
                    }
                }
                return true;
            }
        }

        if (tab == 2 && button == 0) {
            ItemStack st = handler.getSlot(ResearchTableScreenHandler.INPUT_SLOT).getStack();
            if (!st.isEmpty() && (st.isEnchantable() || !EnchantmentHelper.get(st).isEmpty())) {

                final int pad = 5;
                int sideX = guiX + this.backgroundWidth - RIGHT_PANEL_OVERLAP;
                int listLeft   = sideX + pad - 1;
                int listTop    = guiY + 16;
                int listRight  = sideX + RIGHT_PANEL_DRAW_W - pad - 2;
                int listBottom = guiY + this.backgroundHeight - pad - 13;

                if (mouseX >= listLeft && mouseX <= listRight && mouseY >= listTop && mouseY <= listBottom) {
                    buildImbueRows(st);

                    int y = listTop - imbuePanelScroll;
                    int hitIndex = -1;

                    for (int i = 0; i < imbueRows.size(); i++) {
                        ImbueRow r = imbueRows.get(i);
                        int rowH = (r.ench == null) ? IMBUE_ROW_H_HDR : IMBUE_ROW_H_ENCH;
                        int top = y;
                        int bottom = y + rowH - 1;

                        if (mouseY >= top && mouseY < bottom) {
                            hitIndex = i;
                            break;
                        }
                        y += rowH;
                    }

                    if (hitIndex >= 0) {
                        ImbueRow r = imbueRows.get(hitIndex);

                        if (r.ench != null && r.section == Section.UNLOCKED && !r.incompatible) {
                            if (imbueSelected.containsKey(r.ench)) {
                                imbueSelected.remove(r.ench);
                            } else {
                                int lvl = Math.max(1, r.displayLevel);
                                imbueSelected.put(r.ench, lvl);
                            }

                            Map<Enchantment, Integer> cur = EnchantmentHelper.get(st);
                            flagIncompatibles(cur.keySet());
                            computeCosts();

                            sendImbueSelectionsToServer();
                            playClickSound();
                            return true;
                        }
                    }
                }
            }
        }

        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void playClickSound() {
        var mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.playSound(net.minecraft.sound.SoundEvents.UI_BUTTON_CLICK.value(), 0.5f, 1.0f);
        }
    }

    // ---------- misc helpers ----------
    private static String toRoman(int number) {
        if (number <= 0) return "";
        int[] values = {1000,900,500,400,100,90,50,40,10,9,5,4,1};
        String[] numerals = {"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            while (number >= values[i]) {
                number -= values[i];
                sb.append(numerals[i]);
            }
        }
        return sb.toString();
    }

    private List<String> wrapPlain(String s, int maxWidth) {
        List<String> out = new ArrayList<>();
        String[] words = s.split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            String trial = (line.length() == 0) ? w : line + " " + w;
            if (this.textRenderer.getWidth(trial) <= maxWidth) {
                line = new StringBuilder(trial);
            } else {
                if (line.length() > 0) out.add(line.toString());
                line = new StringBuilder(w);
            }
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }

    private String clipToWidth(String s, int maxWidth) {
        if (this.textRenderer.getWidth(s) <= maxWidth) return s;
        String ellipsis = "...";
        int wEll = this.textRenderer.getWidth(ellipsis);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            String trial = out.toString() + s.charAt(i);
            if (this.textRenderer.getWidth(trial) + wEll > maxWidth) break;
            out.append(s.charAt(i));
        }
        return out + ellipsis;
    }

    // marquee that pauses at both ends
    private void drawScrollingText(DrawContext ctx, String text, int sx, int sy, int maxWidth, int color) {
        int textW = this.textRenderer.getWidth(text);
        if (textW <= maxWidth) {
            ctx.drawText(this.textRenderer, Text.literal(text), sx, sy, color, false);
            return;
        }

        final int scrollable = textW - maxWidth;
        final int pxPerTick = 2;
        final int pauseStart = 10;
        final int pauseEnd   = 20;

        final int travelTicks = Math.max(1, (int)Math.ceil(scrollable / (double)pxPerTick));
        final int cycle = pauseStart + travelTicks + pauseEnd;

        int ticks = Objects.requireNonNull(MinecraftClient.getInstance().player).age;
        int t = ticks % cycle;

        int offset;
        if (t < pauseStart) {
            offset = 0;
        } else if (t < pauseStart + travelTicks) {
            offset = Math.min(scrollable, (t - pauseStart) * pxPerTick);
        } else {
            offset = scrollable;
        }

        ctx.enableScissor(sx, sy, sx + maxWidth, sy + 11);
        ctx.drawText(this.textRenderer, Text.literal(text), sx - offset, sy, color, false);
        ctx.disableScissor();
    }

    // data helpers
    private static final class MatEntry {
        final ItemStack stack; final int points;
        MatEntry(ItemStack s, int p) { this.stack = s; this.points = p; }
    }

    private List<MatEntry> getResearchMaterialsFor(String enchId) {
        // New structure: Map<itemId, Map<enchantId, points>>
        List<MatEntry> out = new ArrayList<>();
        ResearchItems.entries().forEach((itemStr, enchMap) -> {
            if (enchMap == null) return;
            Integer pts = enchMap.get(enchId);
            if (pts != null) {
                Identifier itemId = Identifier.tryParse(itemStr);
                if (itemId != null) {
                    Item item = Registries.ITEM.get(itemId);
                    if (item != null) out.add(new MatEntry(new ItemStack(item), Math.max(1, pts)));
                }
            }
        });
        out.sort((a, b) -> Integer.compare(b.points, a.points));
        return out;
    }

    private List<ItemStack> getApplicableIcons(Enchantment ench) {
        ItemStack[] reps = new ItemStack[] {
                new ItemStack(Items.DIAMOND_SWORD),
                new ItemStack(Items.DIAMOND_AXE),
                new ItemStack(Items.DIAMOND_PICKAXE),
                new ItemStack(Items.DIAMOND_SHOVEL),
                new ItemStack(Items.DIAMOND_HOE),
                new ItemStack(Items.BOW),
                new ItemStack(Items.CROSSBOW),
                new ItemStack(Items.TRIDENT),
                new ItemStack(Items.FISHING_ROD),
                new ItemStack(Items.SHEARS),
                new ItemStack(Items.SHIELD),
                new ItemStack(Items.DIAMOND_HELMET),
                new ItemStack(Items.DIAMOND_CHESTPLATE),
                new ItemStack(Items.DIAMOND_LEGGINGS),
                new ItemStack(Items.DIAMOND_BOOTS)
        };
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack s : reps) {
            try { if (ench.isAcceptableItem(s)) list.add(s); } catch (Throwable ignored) {}
        }
        return list;
    }
}