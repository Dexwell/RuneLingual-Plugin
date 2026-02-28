package com.RuneLingual.Widgets;

import com.RuneLingual.*;
import com.RuneLingual.SQL.SqlQuery;
import com.RuneLingual.commonFunctions.Colors;
import com.RuneLingual.commonFunctions.Transformer;
import com.RuneLingual.commonFunctions.Transformer.TransformOption;
import com.RuneLingual.nonLatin.CharImageInit;
import com.RuneLingual.nonLatin.GeneralFunctions;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.widgets.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.gameval.InterfaceID.*;
import net.runelite.client.game.ChatIconManager;

import javax.inject.Inject;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.runelite.api.widgets.WidgetUtil;

import static com.RuneLingual.Widgets.WidgetsUtilRLingual.removeBrAndTags;


@Slf4j
public class DialogTranslator {
    // Dialog happens in a separate widget than the ChatBox itself
    // not limited to npc conversations themselves, but also chat actions
    @Inject
    private Client client;
    @Inject
    private RuneLingualPlugin plugin;
    @Inject
    private RuneLingualConfig config;

    // player widget ids
    @Getter
    private final int playerNameWidgetId = ChatRight.NAME;
    @Getter
    private final int playerContinueWidgetId = ChatRight.CONTINUE;
    @Getter
    private final int playerContentWidgetId = ChatRight.TEXT;

    // npc widget ids
    @Getter
    private final int npcNameWidgetId = ChatLeft.NAME;
    @Getter
    private final int npcContinueWidgetId = ChatLeft.CONTINUE;
    @Getter
    private final int npcContentWidgetId = ChatLeft.TEXT;

    // dialog option widget ids
    @Getter
    private final int dialogOptionWidgetId = Chatmenu.OPTIONS; // each and every line of the option dialogue has this id, even the red "select an option" text


    private final Colors defaultTextColor = Colors.black;
    private final Colors continueTextColor = Colors.blue;
    private final String continueText = "Click here to continue";
    private final Colors nameAndSelectOptionTextColor = Colors.red2;
    private final String selectOptionText = "Select an option";
    private final Colors pleaseWaitTextColor = Colors.blue;
    private final String pleaseWaitText = "Please wait...";

    private TransformOption dialogOption;
    private TransformOption npcNameOption;
    @Inject
    Transformer transformer;

    @Inject
    private GeneralFunctions generalFunctions;
    @Inject
    private CharImageInit charImageInit;
    @Inject
    private WidgetsUtilRLingual widgetsUtilRLingual;

    private static final Pattern IMG_TAG_PATTERN = Pattern.compile("<img=(\\d+)>");
    // Maps shadow sprite img index → noshadow sprite img index
    private Map<Integer, Integer> toNoShadowMap = null;

    @Inject
    public DialogTranslator(RuneLingualConfig config, Client client, RuneLingualPlugin plugin) {
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        this.transformer = new Transformer(plugin);
    }

    /**
     * Build a map from shadow sprite img index → noshadow sprite img index.
     * For each non-noshadow key in charIds, looks up the corresponding "noshadow_" key
     * and maps the shadow chatIconIndex → noshadow chatIconIndex.
     */
    private void buildNoShadowMap() {
        toNoShadowMap = new HashMap<>();
        HashMap<String, Integer> charIds = plugin.getCharIds();
        ChatIconManager chatIconManager = plugin.getChatIconManager();

        for (Map.Entry<String, Integer> entry : charIds.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("noshadow_")) continue;

            Integer noShadowHash = charIds.get("noshadow_" + name);
            if (noShadowHash != null) {
                int shadowIdx = chatIconManager.chatIconIndex(entry.getValue());
                int noShadowIdx = chatIconManager.chatIconIndex(noShadowHash);
                toNoShadowMap.put(shadowIdx, noShadowIdx);
            }
        }
    }

    /**
     * Replace shadow sprite <img> tags with their noshadow equivalents.
     * Dialogue uses a different font style that doesn't have drop shadows.
     */
    private String swapToNoShadow(String text) {
        if (text == null || !text.contains("<img=")) return text;
        if (!plugin.getTargetLanguage().needsCharImages()) return text;

        if (toNoShadowMap == null) {
            buildNoShadowMap();
        }

        Matcher matcher = IMG_TAG_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            int imgIndex = Integer.parseInt(matcher.group(1));
            int noShadowIndex = toNoShadowMap.getOrDefault(imgIndex, imgIndex);
            matcher.appendReplacement(sb, "<img=" + noShadowIndex + ">");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Get the dialogue font name if multi-font mode is active and a dialogue font is available.
     * Returns "quill8" or "quill" if available, null otherwise (fall back to noshadow swap).
     */
    private String getDialogueFont() {
        if (!charImageInit.isMultiFontMode()) return null;
        if (charImageInit.hasFontAvailable("quill8")) return "quill8";
        if (charImageInit.hasFontAvailable("quill")) return "quill";
        if (charImageInit.hasFontAvailable("plain12")) return "plain12";
        return null; // no dialogue font available; will fall back to noshadow swap
    }

    /** Fonts that are dialogue-specific and already rendered without shadows. */
    private static final Set<String> DIALOGUE_FONTS = new HashSet<>(Arrays.asList("quill", "quill8"));

    /** Set widget text, using noshadow swap when the font has shadow variants. */
    private void setDialogText(Widget widget, String text) {
        String dialogueFont = getDialogueFont();
        if (dialogueFont != null && DIALOGUE_FONTS.contains(dialogueFont)) {
            // Dialogue font (quill/quill8) has no shadow variants, no swap needed
            widget.setText(text);
        } else {
            // UI font fallback or legacy mode: swap shadow sprites to noshadow variants
            widget.setText(swapToNoShadow(text));
        }
    }

    /** Saved font context to restore after dialogue translation. */
    private String savedFontContext = null;

    /**
     * Set the dialogue font context before translation calls, and reset after.
     * When a dialogue font (quill8/quill) is available in multi-font mode,
     * StringToTags will use that font's sprites (which have no drop shadow).
     * Saves the previous font context so it can be restored by resetFontContext().
     */
    private void setDialogueFontContext() {
        savedFontContext = generalFunctions.getCurrentFont();
        String dialogueFont = getDialogueFont();
        if (dialogueFont != null) {
            generalFunctions.setCurrentFont(dialogueFont);
        }
    }

    /** Restore the font context that was active before setDialogueFontContext(). */
    private void resetFontContext() {
        generalFunctions.setCurrentFont(savedFontContext);
        savedFontContext = null;
    }

    public void handleDialogs(Widget widget) {
        if(widget.getText().contains("<img=")) {
            return;
        }
        dialogOption = MenuCapture.getTransformOption(plugin.getConfig().getNpcDialogueConfig(), plugin.getConfig().getSelectedLanguage());
        npcNameOption = MenuCapture.getTransformOption(plugin.getConfig().getNPCNamesConfig(), plugin.getConfig().getSelectedLanguage());
        if ((widget.getId() != npcNameWidgetId && dialogOption.equals(TransformOption.AS_IS))
                || (widget.getId() == npcNameWidgetId && npcNameOption.equals(TransformOption.AS_IS))) {
            return;
        }

        int interfaceID = WidgetUtil.componentToInterface(widget.getId());

        // if the widget is the npc name widget, and the config is set to use api translation
        if (npcNameOption.equals(TransformOption.TRANSLATE_API) && widget.getId() == npcNameWidgetId) {
            String npcName = widget.getText();
            setDialogueFontContext();
            try {
                widgetsUtilRLingual.setWidgetText_ApiTranslation(widget, npcName, nameAndSelectOptionTextColor);
            } finally {
                resetFontContext();
            }
            // Dialogue uses a different font style without drop shadows
            setDialogText(widget, widget.getText());
            return;
        }
        // if the widget is not npc name nor player name, and the config is set to use api translation
        else if (dialogOption.equals(TransformOption.TRANSLATE_API) && widget.getId() != playerNameWidgetId && widget.getId() != npcNameWidgetId) {
            String dialogText = widget.getText();
            if(dialogText.isEmpty()) {
                return;
            }
            Colors[] textColor = {defaultTextColor};
            if(widget.getId() == npcContinueWidgetId || widget.getId() == playerContinueWidgetId)
                textColor[0] = continueTextColor;
            else if(widget.getId() == dialogOptionWidgetId && widget.getText().equals(selectOptionText))
                textColor[0] = nameAndSelectOptionTextColor;

            setDialogueFontContext();
            try {
                widgetsUtilRLingual.setWidgetText_ApiTranslation(widget, dialogText, textColor[0]);
            } finally {
                resetFontContext();
            }
            // Dialogue uses a different font style without drop shadows
            setDialogText(widget, widget.getText());
            return;
        }

        // is not api translation
        switch (interfaceID) {
            case InterfaceID.DIALOG_NPC:
                handleNpcDialog(widget);
                return;
            case InterfaceID.DIALOG_PLAYER:
                handlePlayerDialog(widget);
                return;
            case InterfaceID.DIALOG_OPTION:
                handleOptionDialog(widget);
                return;
            default:
                break;
        }
        //log.info("Unknown dialog widget: " + widget.getId());
    }

    // is not api translation
    private void handleNpcDialog(Widget widget) {
        if (widget.getId() == npcNameWidgetId) {
            String npcName = widget.getText();
            npcName = removeBrAndTags(npcName);

            SqlQuery query = new SqlQuery(this.plugin);
            query.setNpcName(npcName, nameAndSelectOptionTextColor);
            setDialogueFontContext();
            String translatedText;
            try {
                translatedText = transformer.transform(npcName, nameAndSelectOptionTextColor,
                        npcNameOption, query, false);
            } finally {
                resetFontContext();
            }
            setDialogText(widget, translatedText);
        } else if (widget.getId() == npcContinueWidgetId) {
            translateContinueWidget(widget);
        } else if (widget.getId() == npcContentWidgetId) {
            String npcContent = widget.getText(); // this can contain tags like <br> and probably color tags
            npcContent = removeBrAndTags(npcContent);
            String npcName = getInteractingNpcName();
            SqlQuery query = new SqlQuery(this.plugin);
            query.setDialogue(npcContent, npcName, false, defaultTextColor);
            setDialogueFontContext();
            String translatedText;
            try {
                translatedText = transformer.transform(npcContent, defaultTextColor, dialogOption, query, false);
            } finally {
                resetFontContext();
            }
            if (getDialogueFont() != null) {
                widgetsUtilRLingual.setWidgetText_NiceBr(widget, translatedText);
            } else {
                widgetsUtilRLingual.setWidgetText_NiceBr(widget, swapToNoShadow(translatedText));
            }
            widgetsUtilRLingual.changeLineHeight(widget);
        }
    }

    // is not api translation
    private void handlePlayerDialog(Widget widget) {
        if (widget.getId() == playerContinueWidgetId) {
            //log.info(widget.getText());
            translateContinueWidget(widget);
            return;
        }
        if (widget.getId() == playerContentWidgetId) {
            String playerContent = widget.getText(); // this can contain tags like <br> and probably color tags
            playerContent = removeBrAndTags(playerContent);


            String npcName = getInteractingNpcName();
            //log.info("playerContent: " + playerContent + " with npc: " + npcName);

            SqlQuery query = new SqlQuery(this.plugin);
            query.setDialogue(playerContent, npcName, true, defaultTextColor);
            setDialogueFontContext();
            String translatedText;
            try {
                translatedText = transformer.transform(playerContent, defaultTextColor, dialogOption, query, false);
            } finally {
                resetFontContext();
            }
            if (getDialogueFont() != null) {
                widgetsUtilRLingual.setWidgetText_NiceBr(widget, translatedText);
            } else {
                widgetsUtilRLingual.setWidgetText_NiceBr(widget, swapToNoShadow(translatedText));
            }
            widgetsUtilRLingual.changeLineHeight(widget);
        }
        // player name does not need to be translated
    }

    private void handleOptionDialog(Widget widget) {
        // the red "Select an option" text is not tagged with red color
        String dialogOption = widget.getText();
        if (dialogOption.equals(selectOptionText)) {
            setDialogText(widget, getSelectOptionTranslation());
            return;
        }
        if (dialogOption.equals(pleaseWaitText)) {
            setDialogText(widget, getPleaseWaitTranslation());
            return;
        }
        dialogOption = removeBrAndTags(dialogOption);
        SqlQuery query = new SqlQuery(this.plugin);
        query.setDialogue(dialogOption, getInteractingNpcName(), false, defaultTextColor);
        setDialogueFontContext();
        String translatedText;
        try {
            translatedText = transformer.transform(dialogOption, defaultTextColor, this.dialogOption, query, false);
        } finally {
            resetFontContext();
        }
        if (getDialogueFont() != null) {
            widgetsUtilRLingual.setWidgetText_NiceBr(widget, translatedText);
        } else {
            widgetsUtilRLingual.setWidgetText_NiceBr(widget, swapToNoShadow(translatedText));
        }
        widgetsUtilRLingual.changeLineHeight(widget);
    }

    private String getInteractingNpcName() {
        NPC npc = plugin.getInteractedNpc();
        if (npc == null) {
            return "";
        }
        return npc.getName();
    }

    private String getContinueTranslation() {
        SqlQuery query = new SqlQuery(this.plugin);
        query.setDialogue(continueText, "", true, continueTextColor);
        setDialogueFontContext();
        try {
            return transformer.transform(continueText, continueTextColor, dialogOption, query, false);
        } finally {
            resetFontContext();
        }
    }

    private String getSelectOptionTranslation() {
        SqlQuery query = new SqlQuery(this.plugin);
        query.setDialogue(selectOptionText, "", true, nameAndSelectOptionTextColor);
        setDialogueFontContext();
        try {
            return transformer.transform(selectOptionText, nameAndSelectOptionTextColor, dialogOption, query, false);
        } finally {
            resetFontContext();
        }
    }

    private String getPleaseWaitTranslation() {
        SqlQuery query = new SqlQuery(this.plugin);
        query.setDialogue(pleaseWaitText, "", true, pleaseWaitTextColor);
        setDialogueFontContext();
        try {
            return transformer.transform(pleaseWaitText, pleaseWaitTextColor, dialogOption, query, false);
        } finally {
            resetFontContext();
        }
    }

    private void translateContinueWidget(Widget widget) {
        if (widget.getText().equals(continueText)) {
            setDialogText(widget, getContinueTranslation());
        } else if (widget.getText().equals(pleaseWaitText)) {
            setDialogText(widget, getPleaseWaitTranslation());
        }
    }
}
