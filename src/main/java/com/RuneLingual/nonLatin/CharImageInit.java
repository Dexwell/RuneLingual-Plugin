package com.RuneLingual.nonLatin;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FilenameFilter;
import java.util.HashMap;

import com.RuneLingual.prepareResources.Downloader;
import com.RuneLingual.RuneLingualPlugin;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ChatIconManager;

import javax.imageio.ImageIO;
import javax.inject.Inject;

@Slf4j
public class CharImageInit {
    @Inject
    RuneLingualPlugin runeLingualPlugin;

    /*
    * Load character images from the local folder, and register them to the chatIconManager.
    * Also registers shadow variants (with a 1px drop shadow) for use in menus.
    * The images are downloaded from the RuneLingual transcript website, which is done in the Downloader class.
     */
    public void loadCharImages()
    {
        //if the target language doesn't need character images, return
        if(!runeLingualPlugin.getTargetLanguage().needsCharImages()){
            return;
        }

        ChatIconManager chatIconManager = runeLingualPlugin.getChatIconManager();
        HashMap<String, Integer> charIds = runeLingualPlugin.getCharIds();

        Downloader downloader = runeLingualPlugin.getDownloader();
        String langCode = downloader.getLangCode();
        final String pathToChar = downloader.getLocalLangFolder().toString() + File.separator + "char_" + langCode;

        String[] charNameArray = getCharList(pathToChar); //list of all characters e.g.　black--3021.png

        for (String imageName : charNameArray) {//register all character images to chatIconManager
            try {
                String fullPath = pathToChar + File.separator + imageName;
                File externalCharImg = new File(fullPath);
                final BufferedImage image = ImageIO.read(externalCharImg);

                // Register shadow version as default — most OSRS UI text has drop shadows
                final BufferedImage shadowed = addDropShadow(image);
                final int shadowCharID = chatIconManager.registerChatIcon(shadowed);
                charIds.put(imageName, shadowCharID);

                // Register original (no shadow) for dialogue, which uses a different font style
                final int charID = chatIconManager.registerChatIcon(image);
                charIds.put("noshadow_" + imageName, charID);
            } catch (Exception e){log.error("error:",e);}
        }
        //log.info("end of making character image hashmap");
    }


    /**
     * Adds a 1px drop shadow at offset (1,1) to match OSRS native text rendering.
     * The returned image is 1px wider and 1px taller to accommodate the shadow.
     */
    private BufferedImage addDropShadow(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage result = new BufferedImage(w + 1, h + 1, BufferedImage.TYPE_INT_ARGB);

        // Draw shadow: for each non-transparent pixel in src, draw a near-black pixel at (x+1, y+1)
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int alpha = (argb >> 24) & 0xFF;
                if (alpha > 0) {
                    // Use near-black (1,1,1) instead of pure black (0,0,0)
                    // because OSRS treats pure black as transparent in sprites
                    result.setRGB(x + 1, y + 1, 0xFF010101);
                }
            }
        }

        // Draw original character on top at (0,0)
        Graphics2D g = result.createGraphics();
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(src, 0, 0, null);
        g.dispose();

        return result;
    }

    public String[] getCharList(String pathToChar) {//get list of names of all characters of every colours)
        FilenameFilter pngFilter = new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".png");
            }
        };
        File colorDir = new File(pathToChar + "/");
        File[] files = colorDir.listFiles(pngFilter); //list of files that end with ".png"

        if (files == null){return new String[]{};}

        String[] charImageNames = new String[files.length];
        for (int j = 0; j < files.length; j++) {
            charImageNames[j] = files[j].getName();
        }
        return charImageNames;
    }
}
