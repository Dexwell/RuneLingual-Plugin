package com.RuneLingual.nonLatin;

import com.RuneLingual.RuneLingualPlugin;
import com.RuneLingual.commonFunctions.Colors;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.game.ChatIconManager;

import javax.inject.Inject;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@javax.inject.Singleton
public class GeneralFunctions {
    @Inject
    private CharImageInit charImageInit;
    @Inject
    private RuneLingualPlugin runeLingualPlugin;

    /** Current font context for StringToTags calls going through Transformer. */
    private String currentFont = null;

    /** Whether to use shadowed sprites (true) or noshadow sprites (false). Default true. */
    private boolean currentUseShadow = true;

    /** Set the font context for subsequent StringToTags calls. Set to null for default font. */
    public void setCurrentFont(String fontName) {
        this.currentFont = fontName;
    }

    /** Get the current font context. */
    public String getCurrentFont() {
        return currentFont;
    }

    /** Set whether to use shadowed or noshadow sprites. */
    public void setCurrentUseShadow(boolean useShadow) {
        this.currentUseShadow = useShadow;
    }

    /** Get current shadow preference. */
    public boolean isCurrentUseShadow() {
        return currentUseShadow;
    }

    public String StringToTags(String string, Colors colors) {
        return StringToTags(string, colors, currentFont);
    }

    /**
     * Font-aware version: looks up sprites with font prefix first, falls back to unprefixed.
     * When fontName is null, only unprefixed lookup is used (backward compatible).
     */
    public String StringToTags(String string, Colors colors, String fontName) {
        /*
        This function takes a string + color and returns emojis that looks like letters
        But leave <img=??> tags as they are (they are already emojis).
        Also leave <asis> tags as they are. This tag can be specified by the translator.
        eg: こんにちは,<img=43>Lukyさん -> <img=43><img=1><img=2><img=3><img=4><img=5><img=6>

        order of conversion : String -> (for each char) -> char to codepoint -> get image name (eg: black--3021.png) ->
        -> get hash value of the char image from the hashmap
        -> get image Id in the chatIcon manager from the hash value
        -> create a tag with the image Id (eg: <img=1>)
        -> repeat to all characters in the string -> append all tags -> return
         */
        String lookBehindPattern = "<img=[0-9]*>|</asis>";
        String lookForwardPattern = "<img=[0-9]*>";
        String asIsPattern = "<asis>.*?</asis>";
        String[] parts = string.split("(?=" + lookForwardPattern + "|"+ asIsPattern +")|(?<=" + lookBehindPattern + ")");
        StringBuilder imgTagSb = new StringBuilder();
        for (String part : parts) {//check for img tags, such as the ironman icon
            if (part.matches(lookForwardPattern)) {//if the part is an img tag, just append it
                imgTagSb.append(part);
                continue;
            }
            if (part.matches(asIsPattern)) {//if the part is an asis tag, append the inside with color tag
                String asIsContent = part.substring(6, part.length() - 7);
                imgTagSb.append("<col=");
                imgTagSb.append(colors.getHex());
                imgTagSb.append(">");
                imgTagSb.append(asIsContent);
                imgTagSb.append("</col>");
                continue;
            }
            StringBuilder imgTagStrings = new StringBuilder();
            ChatIconManager chatIconManager = runeLingualPlugin.getChatIconManager();
            HashMap<String, Integer> map = runeLingualPlugin.getCharIds();
            for (int j = 0; j < part.length(); ) {//if the part is not an img tag, convert each letters to letter emojis

                int codePoint = part.codePointAt(j);
                if (Arrays.asList(32, 160, 8195, 8194, 8201, 8202, 8203, 12288).contains(codePoint)) {//if the char is a space, append a space
                    imgTagStrings.append(" ");
                    j += 1;
                    continue;
                }
                String imgName = colors.getName() + "--" + codePoint + ".png";

                // Font-aware lookup: try font-prefixed key first, fall back to unprefixed.
                // Use noshadow when: currentUseShadow is false, OR color is black
                // (black shadow behind black text is invisible and just makes text look thicker).
                boolean wantNoShadow = !currentUseShadow || colors.getName().startsWith("black");
                int hash = -99;
                if (fontName != null) {
                    if (wantNoShadow) {
                        hash = map.getOrDefault("noshadow_" + fontName + ":" + imgName, -99);
                    }
                    if (hash == -99) {
                        hash = map.getOrDefault(fontName + ":" + imgName, -99);
                    }
                }
                if (hash == -99) {
                    if (wantNoShadow) {
                        hash = map.getOrDefault("noshadow_" + imgName, -99);
                    }
                    if (hash == -99) {
                        hash = map.getOrDefault(imgName, -99);
                    }
                }

                if (hash == -99) {//if the char is not in the hashmap, append a question mark
                    imgTagStrings.append("?");
                    log.error("Char not found in hashmap: char='{}' codepoint={} lookupKey='{}' font='{}' wantNoShadow={} color={}",
                            String.valueOf(Character.toChars(codePoint)), codePoint, imgName, fontName, wantNoShadow, colors.name());
                    j += Character.isHighSurrogate(part.charAt(j)) ? 2 : 1;
                    continue; // bug fix: was missing this continue — would fall through to append invalid img tag
                }
                imgTagStrings.append("<img=");
                imgTagStrings.append(chatIconManager.chatIconIndex(hash));
                imgTagStrings.append(">");
                j += Character.isHighSurrogate(part.charAt(j)) ? 2 : 1;

            }
            imgTagSb.append(imgTagStrings);
        }
        return imgTagSb.toString();
    }

    public static Map<String, String> getPlaceholder2Content(String originalText, String placeholderText) {
        /*
        String originalText = "slay blue dragons in Taverley";
        String placeholderText = "slay <!monster> in <!location>";
        returns: {"monster": "blue dragons", "location": "Taverley"}
         */
        Map<String, String> result = new HashMap<>();
        String[] fixedTexts = placeholderText.split("(?=<![^>]+>)|(?=<Num\\d+>)|(?<=\\w>)"); // fixedTexts = ["slay ", "<!monster>", " in ", "<!location>"]

        StringBuilder regex = new StringBuilder("^");
        List<String> placeholders = new ArrayList<>();

        for (String segment : fixedTexts) {
            if (segment.startsWith("<!") && segment.endsWith(">")) {
                placeholders.add(segment.substring(2, segment.length() - 1));
                regex.append("(.*)");
            } else if(segment.matches("<Num\\d+>")) {
                regex.append("(\\d+)"); // for <Num1>, <Num2>, etc.
            } else {
                regex.append(Pattern.quote(segment));
            }
        }
        regex.append("$"); // regex = "^\Qslay \E(.*)\Q in \E(.*)$", placeholders = ["monster", "location"]

        Pattern pattern = Pattern.compile(regex.toString());
        Matcher matcher = pattern.matcher(originalText);

        if (matcher.find()) {
            for (int i = 0; i < placeholders.size(); i++) {
                result.put(placeholders.get(i), matcher.group(i + 1));
            }
        }
        return result; //{location=Taverley, monster=blue dragons}
    }
}
