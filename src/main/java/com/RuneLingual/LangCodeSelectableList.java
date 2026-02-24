package com.RuneLingual;

import lombok.AccessLevel;
import lombok.Getter;
import net.runelite.api.widgets.Widget;

import javax.inject.Inject;

@Getter
public enum LangCodeSelectableList
{
    ENGLISH ("English", "en", "EN","EN", 8, 14, 6, 6, false, false, false, false, true, false, false),
    PORTUGUÊS_BRASILEIRO ("Brazilian Portuguese", "pt_br", "PT","PT-BR", 8, 11, 6, 6, false, false, false, false, true, false, true),
    NORSK("Norwegian", "no", "NB", "NB", 8, 14, 6, 6, false, false, false, false, true, false, true),
    日本語("Japanese", "ja", "JA", "JA", 12, 12, 12, 15, true, false, true, true, false, true, true),
    Русский("Russian", "ru", "RU", "RU", 8, 12, 6, 6, true, false, true, false, true, false, true),
    český("Czech", "cs", "CS", "CS", 7, 14, 6, 6, false, false, false, false, true, false, false),
    dansk("Danish", "da", "DA", "DA", 7, 14, 6, 6, false, false, false, false, true, false, false),
    DEUTSCH("German", "de", "DE", "DE", 7, 14, 6, 6, false, false, false, false, true, false, false),
    ESPAÑOL("Spanish", "es", "ES", "ES", 7, 14, 6, 6, false, false, false, false, true, false, false),
    eesti("Estonian", "et", "ET", "ET", 7, 14, 6, 6, false, false, false, false, true, false, false),
    suomi("Finnish", "fi", "FI", "FI", 7, 14, 6, 6, false, false, false, false, true, false, false),
    FRANÇAIS("French", "fr", "FR", "FR", 7, 14, 6, 6, false, false, false, false, true, true, false),
    //hrvatski("Croatian", "hr", "HE", "HE", 8, 14, 6, 6, false, true, false, false, true, false, false), // only available in pro v2 api, todo: change code to check for this, then add this language
    magyar("Hungarian", "hu", "HU", "HU", 7, 14, 6, 6, false, false, false, false, true, false, false),
    Indonesian("Indonesian", "id", "ID", "ID", 7, 14, 6, 6, false, false, false, false, true, false, false),
    italiano("Italian", "it", "IT", "IT", 7, 14, 6, 6, false, false, false, false, true, false, false),
    Nederlands("Dutch", "nl", "NL", "NL", 7, 14, 6, 6, false, false, false, false, true, false, false),
    PORTUGUÊS("Portuguese", "pt", "PT-PT", "PT-PT", 7, 14, 6, 6, false, false, false, false, true, false, false),
    svenska("Swedish", "sv", "SV", "SV", 7, 14, 6, 6, false, false, false, false, true, false, false),
    Türkçe("Turkish", "tr", "TR", "TR", 7, 14, 6, 6, false, false, false, false, true, false, true),
    Polski("Polish", "pl", "PL", "PL", 7, 14, 6, 6, false, false, false, false, true, false, false);


    // todo: add languages here
    // needs char images: arabic, bulgarian, czech, greek, hebrew(pro only), lithuanian, latvian, polish, romanian, slovak, slovenian, Thai (pro version only), Ukrainian, Vietnamese (pro version only), Chinese (simplified & traditional)

    private final String displayName;
    private final String langCode;
    private final String deeplLangCodeSource;
    private final String deeplLangCodeTarget;
    private final int charWidth;
    private final int charHeight;
    private final int chatBoxCharWidth;
    private final int overlayCharWidth;

    @Getter(AccessLevel.NONE)
    private final boolean needCharImages;
    @Getter(AccessLevel.NONE)
    private final boolean swapMenuOptionAndTarget;
    @Getter(AccessLevel.NONE)
    private final boolean needInputOverlay;
    @Getter(AccessLevel.NONE)
    private final boolean needInputCandidateOverlay;
    @Getter(AccessLevel.NONE)
    private final boolean needSpaceBetweenWords;
    private final boolean chatButtonHorizontal;
    @Getter(AccessLevel.NONE)
    private final boolean localTranscript;


    @Inject
    LangCodeSelectableList(String displayName, String langCode, String deeplCodeSrc, String deeplCodeTgt,
                           int charWidth, int charHeight, int chatBoxCharWidth, int overlayCharWidth,
                           boolean needCharImages, boolean swapMenuOptionAndTarget,
                           boolean needInputOverlay, boolean needInputCandidateOverlay,
                           boolean needSpaceBetweenWords, boolean chatButtonHorizontal, boolean localTranscript) {
        this.displayName = displayName;
        this.langCode = langCode;
        this.deeplLangCodeSource = deeplCodeSrc;
        this.deeplLangCodeTarget = deeplCodeTgt;
        this.charWidth = charWidth;
        this.charHeight = charHeight;
        this.chatBoxCharWidth = chatBoxCharWidth;
        this.overlayCharWidth = overlayCharWidth;
        this.needCharImages = needCharImages;
        this.swapMenuOptionAndTarget = swapMenuOptionAndTarget;
        this.needInputOverlay = needInputOverlay;
        this.needInputCandidateOverlay = needInputCandidateOverlay;
        this.needSpaceBetweenWords = needSpaceBetweenWords;
        this.chatButtonHorizontal = chatButtonHorizontal;
        this.localTranscript = localTranscript;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public boolean needsCharImages() {
        return needCharImages;
    }

    public boolean needsSwapMenuOptionAndTarget() {
        return swapMenuOptionAndTarget;
    }

    public boolean needsInputOverlay() {
        return needInputOverlay;
    }

    public boolean needsInputCandidateOverlay() {
        return needInputCandidateOverlay;
    }

    public boolean needsSpaceBetweenWords() {
        return needSpaceBetweenWords;
    }

    public boolean hasLocalTranscript() {return localTranscript;}

    public static int getLatinCharWidth(Widget widget, LangCodeSelectableList langCode) {
        /*
        494: 5 px
        495: 6 px
        496: 7 px
        */
        int fontId = widget.getFontId();
        if (fontId == 494) {
            return 5;
        } else if (fontId == 495) {
            return 6;
        } else if (fontId == 496) {
            return 7;
        }
        return langCode.getCharWidth();
    }

    public static String getAPIErrorMessage(LangCodeSelectableList langCode) {
        if (langCode == LangCodeSelectableList.日本語) {
            return "APIキーが無効、翻訳の上限が近い、\nもしくはリクエストが集中しています。";
        } else {
            return "The API key is invalid, the translation \nlimit is close, or requests are congested.";
        }
    }
}
/*
* deepl lang codes: https://developers.deepl.com/docs/resources/supported-languages#target-languages
* */