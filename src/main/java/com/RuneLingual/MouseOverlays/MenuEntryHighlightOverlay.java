package com.RuneLingual.MouseOverlays;

import com.RuneLingual.LangCodeSelectableList;
import com.RuneLingual.RuneLingualConfig;
import com.RuneLingual.RuneLingualPlugin;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

@Slf4j
public class MenuEntryHighlightOverlay extends Overlay {
    private final Client client;
    private final RuneLingualConfig config;
    @Inject
    private RuneLingualPlugin plugin;
    @Setter
    private static List<String> attemptedTranslation = Collections.synchronizedList(new ArrayList<>());

    // OSRS right-click menu layout constants
    private static final int MENU_HEADER_HEIGHT = 19; // "Choose Option" header + border + separator
    private static final int MENU_ENTRY_HEIGHT = 15;  // each menu row is 15px tall
    private static final Pattern IMG_TAG_PATTERN = Pattern.compile("<img=(\\d+)>");

    // Maps any registered img index to its yellow-colored equivalent
    private Map<Integer, Integer> toYellowMap = null;

    // Track hovered state to restore original option text
    private int lastHoveredVisualRow = -1;
    private String lastOriginalOption = null;

    @Inject
    public MenuEntryHighlightOverlay(Client client, RuneLingualConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(PRIORITY_HIGH);
    }

    /**
     * Build a mapping from any registered img index to the yellow sprite for the same codepoint.
     * e.g. if charIds has "white--3021.png" -> imgIndex 42 and "yellow--3021.png" -> imgIndex 87,
     * then toYellowMap[42] = 87.
     * Only maps default (shadow) sprites; noshadow variants are used only in dialogue.
     */
    private void buildColorSwapMap() {
        toYellowMap = new HashMap<>();
        HashMap<String, Integer> charIds = plugin.getCharIds();
        ChatIconManager chatIconManager = plugin.getChatIconManager();

        if (charIds == null || charIds.isEmpty()) {
            return;
        }

        // First pass: collect yellow sprites by codepoint
        // Only use unprefixed (default font) entries; skip font-prefixed (e.g. "plain12:yellow--3021.png")
        // and noshadow variants since menus always use the default shadowed sprites.
        Map<String, Integer> yellowByCodepoint = new HashMap<>();
        for (Map.Entry<String, Integer> entry : charIds.entrySet()) {
            String imageName = entry.getKey();
            // Skip noshadow variants — they are only used in dialogue, not menus
            if (imageName.startsWith("noshadow_")) continue;
            // Skip font-prefixed entries (e.g. "plain12:yellow--3021.png") — only use default font
            if (imageName.contains(":")) continue;

            if (imageName.startsWith("yellow--")) {
                String codepoint = imageName.substring("yellow--".length(), imageName.length() - 4);
                int imgIndex = chatIconManager.chatIconIndex(entry.getValue());
                if (imgIndex >= 0) {
                    yellowByCodepoint.put(codepoint, imgIndex);
                }
            }
        }

        // Second pass: map every non-yellow default sprite to its yellow equivalent
        for (Map.Entry<String, Integer> entry : charIds.entrySet()) {
            String imageName = entry.getKey();
            if (imageName.startsWith("noshadow_")) continue;
            if (imageName.contains(":")) continue;

            int dashIdx = imageName.indexOf("--");
            if (dashIdx < 0) continue;
            String codepoint = imageName.substring(dashIdx + 2, imageName.length() - 4);
            int srcImgIndex = chatIconManager.chatIconIndex(entry.getValue());
            Integer yellowImgIndex = yellowByCodepoint.get(codepoint);
            if (srcImgIndex >= 0 && yellowImgIndex != null) {
                toYellowMap.put(srcImgIndex, yellowImgIndex);
            }
        }
    }

    /**
     * Replace all <img=X> tags in the text with their yellow equivalents.
     */
    private String swapToYellow(String text) {
        if (toYellowMap == null || toYellowMap.isEmpty() || text == null) {
            return text;
        }
        Matcher matcher = IMG_TAG_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            int imgIndex = Integer.parseInt(matcher.group(1));
            int yellowIndex = toYellowMap.getOrDefault(imgIndex, imgIndex);
            matcher.appendReplacement(sb, "<img=" + yellowIndex + ">");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public void resetSwapMap() {
        toYellowMap = null;
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        if (!client.isMenuOpen()
                || !plugin.getTargetLanguage().needsCharImages()
                || plugin.getTargetLanguage() == LangCodeSelectableList.ENGLISH) {
            // Menu closed or not applicable — reset hover tracking
            if (lastHoveredVisualRow != -1) {
                lastHoveredVisualRow = -1;
                lastOriginalOption = null;
            }
            return null;
        }

        // Lazily build the color swap map
        if (toYellowMap == null) {
            buildColorSwapMap();
        }

        Menu menu = client.getMenu();
        int menuX = menu.getMenuX();
        int menuY = menu.getMenuY();
        int menuWidth = menu.getMenuWidth();

        MenuEntry[] entries = menu.getMenuEntries();
        int entryCount = entries.length;
        if (entryCount == 0) {
            return null;
        }

        // Get mouse position on the game canvas
        net.runelite.api.Point mousePos = client.getMouseCanvasPosition();
        int mouseX = mousePos.getX();
        int mouseY = mousePos.getY();

        // Calculate which visual row is hovered
        int entriesStartY = menuY + MENU_HEADER_HEIGHT;
        int mouseRelY = mouseY - entriesStartY;

        int hoveredVisualRow = -1;
        if (mouseRelY >= 0 && mouseX >= menuX && mouseX <= menuX + menuWidth) {
            int row = mouseRelY / MENU_ENTRY_HEIGHT;
            if (row < entryCount) {
                hoveredVisualRow = row;
            }
        }

        // Visual rows map to array indices in reverse:
        // visual row 0 (top) = entries[entryCount - 1]
        // visual row 1 = entries[entryCount - 2], etc.

        // Restore the previously hovered entry to its original white option
        if (lastHoveredVisualRow != hoveredVisualRow && lastHoveredVisualRow >= 0) {
            int lastArrayIdx = entryCount - 1 - lastHoveredVisualRow;
            if (lastArrayIdx >= 0 && lastArrayIdx < entryCount && lastOriginalOption != null) {
                entries[lastArrayIdx].setOption(lastOriginalOption);
            }
            lastOriginalOption = null;
        }

        // Set the newly hovered entry's option to yellow
        if (hoveredVisualRow >= 0) {
            int arrayIdx = entryCount - 1 - hoveredVisualRow;
            if (arrayIdx >= 0 && arrayIdx < entryCount) {
                MenuEntry hovered = entries[arrayIdx];
                // Only save the original if we just started hovering this entry
                if (lastHoveredVisualRow != hoveredVisualRow) {
                    lastOriginalOption = hovered.getOption();
                }
                String yellowOption = swapToYellow(lastOriginalOption);
                hovered.setOption(yellowOption);
            }
        }

        lastHoveredVisualRow = hoveredVisualRow;
        return null;
    }
}
