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
     * Build a map from any registered img index to its yellow-colored equivalent.
     * For each non-yellow, non-noshadow, unprefixed key in charIds, looks up
     * the corresponding "yellow--codepoint.png" key and maps the img indices.
     */
    private void buildColorSwapMap() {
        toYellowMap = new HashMap<>();
        HashMap<String, Integer> charIds = plugin.getCharIds();
        ChatIconManager chatIconManager = plugin.getChatIconManager();

        for (Map.Entry<String, Integer> entry : charIds.entrySet()) {
            String name = entry.getKey();
            // Only match unprefixed, non-noshadow entries (default font shadow sprites)
            if (name.startsWith("noshadow_") || name.contains(":")) continue;
            if (name.startsWith("yellow--")) continue;

            int dashIdx = name.indexOf("--");
            if (dashIdx < 0) continue;
            String codepoint = name.substring(dashIdx + 2); // "codepoint.png"
            String yellowKey = "yellow--" + codepoint;

            Integer yellowHash = charIds.get(yellowKey);
            if (yellowHash != null) {
                int srcIdx = chatIconManager.chatIconIndex(entry.getValue());
                int yellowIdx = chatIconManager.chatIconIndex(yellowHash);
                toYellowMap.put(srcIdx, yellowIdx);
            }
        }
    }

    /**
     * Replace all img tags in the text with their yellow equivalents.
     */
    private String swapToYellow(String text) {
        if (text == null || !text.contains("<img=")) return text;

        if (toYellowMap == null) {
            buildColorSwapMap();
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

        // Build the color swap map on first use
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
