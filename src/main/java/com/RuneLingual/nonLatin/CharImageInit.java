package com.RuneLingual.nonLatin;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FilenameFilter;
import java.util.*;

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
     * Tint colors — white source sprites are tinted to each of these at startup.
     * Format: { colorName, R, G, B }.
     * "black" uses (1,1,1) because OSRS treats pure (0,0,0) as transparent.
     */
    private static final String[][] TINT_COLORS = {
            {"black",     "1",   "1",   "1"  },
            {"blue",      "0",   "0",   "255"},
            {"darkred",   "128", "0",   "0"  },
            {"green",     "0",   "255", "0"  },
            {"lightblue", "0",   "255", "255"},
            {"orange",    "255", "112", "0"  },
            {"red",       "255", "0",   "0"  },
            {"white",     "255", "255", "255"},
            {"yellow",    "255", "255", "0"  },
    };

    /** Map from colorName to {R, G, B} for quick lookup. */
    private static final Map<String, int[]> COLOR_RGB = new HashMap<>();
    static {
        for (String[] def : TINT_COLORS) {
            COLOR_RGB.put(def[0], new int[]{
                    Integer.parseInt(def[1]),
                    Integer.parseInt(def[2]),
                    Integer.parseInt(def[3])
            });
        }
    }

    /** Fonts that were detected. */
    @Getter
    private final Set<String> availableFonts = new LinkedHashSet<>();

    /** The default font name (used for unprefixed lookups), or null if legacy mode. */
    @Getter
    private String defaultFont = null;

    /** Path to the char image directory (set during indexing). */
    private String pathToChar = null;

    /** Whether plain11 font is available (cached for alias logic). */
    private boolean plain11Available = false;

    /**
     * Eagerly load all character images: read each white source PNG once,
     * tint to all 9 colors, create shadow/noshadow variants, and register with ChatIconManager.
     *
     * Uses a local cache so each white PNG is only read from disk once across all color tints.
     * Combined with charImagesAlreadyLoaded() in RuneLingualPlugin, this means switching
     * between language variants that share sprites (e.g. ja ↔ ja_nk) is instant.
     */
    public void loadCharImages() {
        if (!runeLingualPlugin.getTargetLanguage().needsCharImages()) {
            return;
        }

        Downloader downloader = runeLingualPlugin.getDownloader();
        String langCode = downloader.getLangCode();

        // Check for this language's own char directory first (e.g., ja_nk/char_ja_nk)
        pathToChar = downloader.getLocalLangFolder().toString() + File.separator + "char_" + langCode;

        // If not found, use the base language's char directory (e.g., ja/char_ja)
        if (!new File(pathToChar).isDirectory()) {
            String charLangCode = runeLingualPlugin.getTargetLanguage().getCharImageLangCode();
            File baseFolder = new File(Downloader.getLocalBaseFolder(), charLangCode);
            pathToChar = new File(baseFolder, "char_" + charLangCode).getPath();
        }

        // Check for font subdirectories
        String[] fontDirs = detectFontDirectories(pathToChar);

        if (fontDirs.length > 0) {
            loadMultiFont(fontDirs);
        } else {
            loadLegacy();
        }
    }

    /**
     * Multi-font mode: for each font directory, read each white PNG once,
     * then tint to all 9 colors and register all key variants.
     */
    private void loadMultiFont(String[] fontDirs) {
        defaultFont = determineDefaultFont(fontDirs);
        plain11Available = false;

        HashMap<String, Integer> charIds = runeLingualPlugin.getCharIds();
        ChatIconManager chatIconManager = runeLingualPlugin.getChatIconManager();

        long startTime = System.currentTimeMillis();
        int totalRegistered = 0;

        for (String fontDir : fontDirs) {
            availableFonts.add(fontDir);
            if (fontDir.equals("plain11")) plain11Available = true;

            String fontPath = pathToChar + File.separator + fontDir;
            String[] codepointFiles = getCharList(fontPath);

            boolean isUiFont = UI_FONTS.contains(fontDir);
            boolean isDefault = fontDir.equals(defaultFont);

            for (String codepointPng : codepointFiles) {
                // Read the white source PNG once
                BufferedImage whiteSprite;
                try {
                    whiteSprite = ImageIO.read(new File(fontPath + File.separator + codepointPng));
                } catch (Exception e) {
                    log.error("Error reading sprite: {}/{}", fontDir, codepointPng, e);
                    continue;
                }

                // Tint to each color and register
                for (String[] colorDef : TINT_COLORS) {
                    String colorName = colorDef[0];
                    int r = Integer.parseInt(colorDef[1]);
                    int g = Integer.parseInt(colorDef[2]);
                    int b = Integer.parseInt(colorDef[3]);

                    BufferedImage tinted = tintSprite(whiteSprite, r, g, b);
                    String imgName = colorName + "--" + codepointPng;

                    if (isUiFont) {
                        BufferedImage shadowed = addDropShadow(tinted);
                        int shadowHash = chatIconManager.registerChatIcon(shadowed);
                        int plainHash = chatIconManager.registerChatIcon(tinted);

                        charIds.put(fontDir + ":" + imgName, shadowHash);
                        charIds.put("noshadow_" + fontDir + ":" + imgName, plainHash);

                        if (isDefault) {
                            charIds.put(imgName, shadowHash);
                            charIds.put("noshadow_" + imgName, plainHash);
                        }

                        // plain12 also serves plain11 if plain11 is not available
                        if (fontDir.equals("plain12") && !plain11Available) {
                            charIds.put("plain11:" + imgName, shadowHash);
                            charIds.put("noshadow_plain11:" + imgName, plainHash);
                        }

                        totalRegistered += 2;
                    } else {
                        int plainHash = chatIconManager.registerChatIcon(tinted);
                        charIds.put(fontDir + ":" + imgName, plainHash);

                        if (isDefault) {
                            charIds.put(imgName, plainHash);
                        }

                        totalRegistered += 1;
                    }
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Multi-font mode: registered {} sprites for fonts {} (default: {}) in {}ms",
                totalRegistered, availableFonts, defaultFont, elapsed);
    }

    /**
     * Legacy mode: load pre-colored PNGs and register with shadow/noshadow variants.
     */
    private void loadLegacy() {
        HashMap<String, Integer> charIds = runeLingualPlugin.getCharIds();
        ChatIconManager chatIconManager = runeLingualPlugin.getChatIconManager();

        String[] files = getCharList(pathToChar);
        long startTime = System.currentTimeMillis();
        int totalRegistered = 0;

        for (String imgName : files) {
            try {
                BufferedImage image = ImageIO.read(new File(pathToChar + File.separator + imgName));
                BufferedImage shadowed = addDropShadow(image);
                int shadowHash = chatIconManager.registerChatIcon(shadowed);
                int plainHash = chatIconManager.registerChatIcon(image);
                charIds.put(imgName, shadowHash);
                charIds.put("noshadow_" + imgName, plainHash);
                totalRegistered += 2;
            } catch (Exception e) {
                log.error("Error loading legacy sprite: {}", imgName, e);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Legacy mode: registered {} sprites from {} in {}ms", totalRegistered, pathToChar, elapsed);
    }

    // ---- Utility methods ----

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

    private String determineDefaultFont(String[] fontDirs) {
        Set<String> available = new LinkedHashSet<>(Arrays.asList(fontDirs));
        if (available.contains("bold12")) return "bold12";
        if (available.contains("plain12")) return "plain12";
        if (available.contains("plain11")) return "plain11";
        return fontDirs[0];
    }

    private BufferedImage tintSprite(BufferedImage src, int targetR, int targetG, int targetB) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        int clampedR = Math.max(targetR, 1);
        int clampedG = Math.max(targetG, 1);
        int clampedB = Math.max(targetB, 1);
        int targetArgbNoAlpha = (clampedR << 16) | (clampedG << 8) | clampedB;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                if (a == 0) {
                    result.setRGB(x, y, 0);
                } else {
                    result.setRGB(x, y, (a << 24) | targetArgbNoAlpha);
                }
            }
        }
        return result;
    }

    private BufferedImage addDropShadow(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage result = new BufferedImage(w + 1, h + 1, BufferedImage.TYPE_INT_ARGB);

        int shadowColor = 0xFF010101;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >> 24) & 0xFF;
                if (a > 0) {
                    result.setRGB(x + 1, y + 1, shadowColor);
                }
            }
        }

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

    public boolean hasFontAvailable(String fontName) {
        return availableFonts.contains(fontName);
    }

    public boolean isMultiFontMode() {
        return defaultFont != null;
    }

    public String[] getCharList(String pathToChar) {
        FilenameFilter pngFilter = (dir, name) -> name.toLowerCase().endsWith(".png");
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
