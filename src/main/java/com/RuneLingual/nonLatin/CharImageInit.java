package com.RuneLingual.nonLatin;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FilenameFilter;
import java.util.*;
import java.util.List;

import com.RuneLingual.prepareResources.Downloader;
import com.RuneLingual.RuneLingualPlugin;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ChatIconManager;

import javax.imageio.ImageIO;
import javax.inject.Inject;

@Slf4j
@javax.inject.Singleton
public class CharImageInit {
    @Inject
    RuneLingualPlugin runeLingualPlugin;

    /**
     * Known OSRS font directory names.
     * If any of these exist as subdirectories under char_{langCode}/,
     * we switch to multi-font mode and load white sprites per font.
     */
    private static final Set<String> KNOWN_FONT_NAMES = new LinkedHashSet<>(Arrays.asList(
            "plain11", "plain12", "bold12", "quill", "quill8"
    ));

    /** UI fonts get drop shadows; dialogue fonts do not. */
    private static final Set<String> UI_FONTS = new LinkedHashSet<>(Arrays.asList(
            "plain11", "plain12", "bold12"
    ));

    /**
     * Tint colors — white source sprites are tinted to each of these at load time.
     * Format: { colorName, R, G, B }.
     * "black" uses (1,1,1) because OSRS treats pure (0,0,0) as transparent.
     */
    private static final String[][] TINT_COLORS = {
            {"black",     "1",   "1",   "1"  },
            {"blue",      "0",   "0",   "255"},
            {"green",     "0",   "255", "0"  },
            {"lightblue", "0",   "255", "255"},
            {"orange",    "255", "112", "0"  },
            {"red",       "255", "0",   "0"  },
            {"white",     "255", "255", "255"},
            {"yellow",    "255", "255", "0"  },
    };

    /** Fonts that were detected and loaded. */
    @Getter
    private final Set<String> availableFonts = new LinkedHashSet<>();

    /** The default font name (used for unprefixed lookups), or null if legacy mode. */
    @Getter
    private String defaultFont = null;

    /*
     * Load character images from the local folder, and register them to the chatIconManager.
     * Detects whether multi-font directories exist; if so, loads white sprites per font
     * and tints them to all colors at runtime. Otherwise falls back to legacy pre-colored PNGs.
     */
    public void loadCharImages() {
        if (!runeLingualPlugin.getTargetLanguage().needsCharImages()) {
            return;
        }

        ChatIconManager chatIconManager = runeLingualPlugin.getChatIconManager();
        HashMap<String, Integer> charIds = runeLingualPlugin.getCharIds();

        Downloader downloader = runeLingualPlugin.getDownloader();
        String langCode = downloader.getLangCode();
        final String pathToChar = downloader.getLocalLangFolder().toString() + File.separator + "char_" + langCode;

        // Check for font subdirectories
        String[] fontDirs = detectFontDirectories(pathToChar);

        if (fontDirs.length > 0) {
            // Multi-font mode: load white sprites per font, tint to all colors
            defaultFont = determineDefaultFont(fontDirs);
            for (String fontDir : fontDirs) {
                availableFonts.add(fontDir);
            }
            loadMultiFontCharImages(pathToChar, fontDirs, chatIconManager, charIds);
            log.info("Multi-font mode: loaded fonts {} (default: {})", availableFonts, defaultFont);
        } else {
            // Legacy mode: load pre-colored PNGs from flat directory
            loadLegacyCharImages(pathToChar, chatIconManager, charIds);
            log.info("Legacy mode: loaded pre-colored sprites from {}", pathToChar);
        }
    }

    /**
     * Check which known font subdirectories exist under pathToChar.
     */
    private String[] detectFontDirectories(String pathToChar) {
        List<String> found = new ArrayList<>();
        for (String fontName : KNOWN_FONT_NAMES) {
            File dir = new File(pathToChar + File.separator + fontName);
            if (dir.isDirectory()) {
                found.add(fontName);
            }
        }
        return found.toArray(new String[0]);
    }

    /**
     * Determine the default font from available font directories.
     * Preference: bold12 > plain12 > plain11 > first available.
     * bold12 is preferred because OSRS uses it for menus, hover text,
     * and most UI elements. plain12 is for longer body text (widgets, chat).
     */
    private String determineDefaultFont(String[] fontDirs) {
        Set<String> available = new LinkedHashSet<>(Arrays.asList(fontDirs));
        if (available.contains("bold12")) return "bold12";
        if (available.contains("plain12")) return "plain12";
        if (available.contains("plain11")) return "plain11";
        return fontDirs[0]; // fallback to first found
    }

    /**
     * Multi-font mode: for each font directory, load white PNGs, tint to each color,
     * and register with/without shadow based on font type.
     *
     * Registration keys:
     *   - UI fonts (shadow):    "fontName:color--codepoint.png" → shadow sprite hash
     *   - UI fonts (noshadow):  "noshadow_fontName:color--codepoint.png" → plain sprite hash
     *   - Default font also gets unprefixed keys: "color--codepoint.png" and "noshadow_color--codepoint.png"
     *   - plain12 also serves plain11 if plain11 not available (registered under "plain11:..." keys)
     *   - Dialogue fonts: "fontName:color--codepoint.png" → plain sprite hash (no shadow, no noshadow variant)
     */
    private void loadMultiFontCharImages(String pathToChar, String[] fontDirs,
                                         ChatIconManager chatIconManager,
                                         HashMap<String, Integer> charIds) {
        boolean plain11Available = availableFonts.contains("plain11");

        for (String fontName : fontDirs) {
            String fontPath = pathToChar + File.separator + fontName;
            String[] whiteFiles = getCharList(fontPath);
            boolean isUiFont = UI_FONTS.contains(fontName);
            boolean isDefault = fontName.equals(defaultFont);

            for (String whiteFileName : whiteFiles) {
                try {
                    File file = new File(fontPath + File.separator + whiteFileName);
                    BufferedImage whiteSprite = ImageIO.read(file);

                    // Extract codepoint from filename: "3021.png" → "3021"
                    String codepointStr = whiteFileName.substring(0, whiteFileName.length() - 4);

                    for (String[] colorDef : TINT_COLORS) {
                        String colorName = colorDef[0];
                        int targetR = Integer.parseInt(colorDef[1]);
                        int targetG = Integer.parseInt(colorDef[2]);
                        int targetB = Integer.parseInt(colorDef[3]);

                        BufferedImage tinted = tintWhiteSprite(whiteSprite, targetR, targetG, targetB);
                        String imgName = colorName + "--" + codepointStr + ".png";

                        if (isUiFont) {
                            // UI font: register shadow version and noshadow version
                            BufferedImage shadowed = addDropShadow(tinted);

                            // Font-prefixed keys
                            String fontKey = fontName + ":" + imgName;
                            String noshadowFontKey = "noshadow_" + fontName + ":" + imgName;
                            int shadowHash = chatIconManager.registerChatIcon(shadowed);
                            int plainHash = chatIconManager.registerChatIcon(tinted);
                            charIds.put(fontKey, shadowHash);
                            charIds.put(noshadowFontKey, plainHash);

                            // Default font also gets unprefixed keys
                            if (isDefault) {
                                charIds.put(imgName, shadowHash);
                                charIds.put("noshadow_" + imgName, plainHash);
                            }

                            // plain12 also serves plain11 if plain11 is not available
                            if (fontName.equals("plain12") && !plain11Available) {
                                charIds.put("plain11:" + imgName, shadowHash);
                                charIds.put("noshadow_plain11:" + imgName, plainHash);
                            }
                        } else {
                            // Dialogue font: register without shadow, no noshadow variant needed
                            String fontKey = fontName + ":" + imgName;
                            int plainHash = chatIconManager.registerChatIcon(tinted);
                            charIds.put(fontKey, plainHash);

                            // If this dialogue font is also somehow the default, register unprefixed
                            if (isDefault) {
                                charIds.put(imgName, plainHash);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("Error loading multi-font sprite: {}/{}", fontName, whiteFileName, e);
                }
            }
        }
    }

    /**
     * Legacy mode: load pre-colored PNGs directly (e.g. "yellow--3021.png").
     * Registers shadow version under the original name and noshadow version under "noshadow_" prefix.
     */
    private void loadLegacyCharImages(String pathToChar,
                                      ChatIconManager chatIconManager,
                                      HashMap<String, Integer> charIds) {
        String[] charNameArray = getCharList(pathToChar);

        for (String imageName : charNameArray) {
            try {
                String fullPath = pathToChar + File.separator + imageName;
                File externalCharImg = new File(fullPath);
                final BufferedImage image = ImageIO.read(externalCharImg);

                // Shadow version (default for UI/menus)
                BufferedImage shadowed = addDropShadow(image);
                final int shadowHash = chatIconManager.registerChatIcon(shadowed);
                charIds.put(imageName, shadowHash);

                // Noshadow version (for dialogue)
                final int plainHash = chatIconManager.registerChatIcon(image);
                charIds.put("noshadow_" + imageName, plainHash);
            } catch (Exception e) {
                log.error("error:", e);
            }
        }
    }

    /**
     * Tint a white sprite to a target color by multiplying each pixel's RGB channels.
     * Pure white (255,255,255) → target color. Gray pixels → proportionally darker.
     * Alpha channel is preserved. Minimum RGB value is 1 (to avoid OSRS transparency).
     */
    private BufferedImage tintWhiteSprite(BufferedImage white, int targetR, int targetG, int targetB) {
        int w = white.getWidth();
        int h = white.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = white.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                if (a == 0) {
                    result.setRGB(x, y, 0); // fully transparent
                    continue;
                }
                int srcR = (argb >> 16) & 0xFF;
                int srcG = (argb >> 8) & 0xFF;
                int srcB = argb & 0xFF;

                // Multiply: newChannel = (srcChannel * targetChannel) / 255
                // Clamp to minimum 1 to avoid OSRS treating (0,0,0) as transparent
                int newR = Math.max((srcR * targetR) / 255, 1);
                int newG = Math.max((srcG * targetG) / 255, 1);
                int newB = Math.max((srcB * targetB) / 255, 1);

                result.setRGB(x, y, (a << 24) | (newR << 16) | (newG << 8) | newB);
            }
        }
        return result;
    }

    /**
     * Add a 1px drop shadow at offset (+1, +1) using near-black (1,1,1).
     * The shadow is drawn behind the original image on a larger canvas.
     */
    private BufferedImage addDropShadow(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        // Canvas is 1px wider and 1px taller to accommodate shadow offset
        BufferedImage result = new BufferedImage(w + 1, h + 1, BufferedImage.TYPE_INT_ARGB);

        // Draw shadow first (offset +1, +1)
        int shadowColor = 0xFF010101; // near-black, not pure black (which OSRS treats as transparent)
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                if (a > 0) {
                    result.setRGB(x + 1, y + 1, shadowColor);
                }
            }
        }

        // Draw original image on top (offset 0, 0)
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                if (a > 0) {
                    result.setRGB(x, y, argb);
                }
            }
        }

        return result;
    }

    /** Check if a specific font is available (was detected and loaded). */
    public boolean hasFontAvailable(String fontName) {
        return availableFonts.contains(fontName);
    }

    /** Whether multi-font mode is active (at least one font directory was found). */
    public boolean isMultiFontMode() {
        return defaultFont != null;
    }

    /** Get list of PNG filenames in a directory. */
    public String[] getCharList(String pathToChar) {
        FilenameFilter pngFilter = new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".png");
            }
        };
        File colorDir = new File(pathToChar + "/");
        File[] files = colorDir.listFiles(pngFilter);

        if (files == null) { return new String[]{}; }

        String[] charImageNames = new String[files.length];
        for (int j = 0; j < files.length; j++) {
            charImageNames[j] = files[j].getName();
        }
        return charImageNames;
    }
}
