package net.runelite.client.plugins.banktagnavigator;

import com.google.inject.Provides;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.ScriptID;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
        name = "Bank Tag Navigator",
        description = "Sectioned navigation for Bank Tags '+', with optional filtered Tag Tab Tab picker.",
        tags = {"bank", "tags", "navigation", "sections"}
)
public class BankTagNavigatorPlugin extends Plugin
{
    /*
     * Bank Tags config keys (used to read the list of tabs + hidden state, without injecting Bank Tags internals)
     */
    private static final String BANKTAGS_GROUP = "banktags";
    private static final String TAG_TABS_CONFIG_KEY = "tagtabs";
    private static final String TAG_HIDDEN_PREFIX = "hidden_";
    private static final String BANKTAGS_ACTIVE_TAB_KEY = "tab";

    /*
     * Bank Tags plugin + TabInterface strings
     */
    private static final String BANKTAGS_PLUGIN_CLASS = "net.runelite.client.plugins.banktags.BankTagsPlugin";
    private static final String OPEN_TAB_MENU = "View tag tabs";   // '+' widget menu entry
    private static final String TAGTABS = "tagtabs";               // special picker tab
    private static final String VIEW_TAB_ACTION = "View tag tab";  // tile action in Tag Tab Tab picker

    private static final String UNORGANIZED = "Unorganized";

    /*
     * Picker tile geometry (matches Bank Tags)
     */
    private static final int BANK_ITEM_WIDTH = BankTagsPlugin.BANK_ITEM_WIDTH;
    private static final int BANK_ITEM_HEIGHT = BankTagsPlugin.BANK_ITEM_HEIGHT;
    private static final int BANK_ITEM_X_PADDING = BankTagsPlugin.BANK_ITEM_X_PADDING;
    private static final int BANK_ITEM_Y_PADDING = BankTagsPlugin.BANK_ITEM_Y_PADDING;
    private static final int BANK_ITEMS_PER_ROW = BankTagsPlugin.BANK_ITEMS_PER_ROW;
    private static final int BANK_ITEM_START_X = BankTagsPlugin.BANK_ITEM_START_X;
    private static final int BANK_ITEM_START_Y = BankTagsPlugin.BANK_ITEM_START_Y;

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ConfigManager configManager;
    @Inject private PluginManager pluginManager;

    @Inject private BankTagNavigatorConfig config;

    private Plugin bankTagsPlugin;

    /*
     * Pending filtered picker request (set when clicking a section in the '+' menu)
     */
    private String pendingPrefix;        // standardized, e.g. "test|one"
    private boolean pendingUnorganized;  // true => show only tags without delimiter
    private String pendingDelimiter;     // e.g. "|"
    private int pendingTries;            // retry a few finishbuilding passes for timing

    /*
     * Active filtered picker state (used to rewrite only the VIEW_TAB_ACTION target to relative names)
     */
    private String activePickerPrefix;        // standardized, e.g. "test|one"
    private boolean activePickerUnorganized;
    private String activePickerDelimiter;

    @Provides
    BankTagNavigatorConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(BankTagNavigatorConfig.class);
    }

    @Subscribe
    public void onMenuOpened(MenuOpened event)
    {
        // Only if Bank Tags is active
        if (getActiveBankTagsPlugin() == null)
        {
            return;
        }

        // Only the '+' widget menu (it contains OPEN_TAB_MENU)
        final MenuEntry marker = findEntry(event.getMenuEntries(), OPEN_TAB_MENU);
        if (marker == null)
        {
            return;
        }

        final List<String> tabs = loadTagTabs(config.showHiddenTags());
        if (tabs.isEmpty())
        {
            return;
        }

        final String delim = sanitizeDelimiter(config.delimiter());
        final Node root = buildTree(tabs, delim);

        final Menu menu = client.getMenu();

        if (config.hideDefaultViewTagTabs())
        {
            removeMatchingEntry(menu, marker);
        }

        final int p0 = marker.getParam0();
        final int p1 = marker.getParam1();

        buildPlusMenu(menu, root, delim, "", true, p0, p1);
    }

    @Subscribe
    public void onScriptPreFired(ScriptPreFired event)
    {
        if (event.getScriptId() != ScriptID.BANKMAIN_FINISHBUILDING)
        {
            return;
        }

        // If we requested a filtered picker, apply after Bank Tags builds the Tag Tab Tab tiles
        if ((pendingPrefix == null && !pendingUnorganized) || pendingDelimiter == null)
        {
            return;
        }

        clientThread.invokeLater(this::applyPendingPickerFilter);
    }

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        // Only rewrite while Bank Tags is in TAGTABS mode
        if (!isInTagTabsPicker())
        {
            clearActivePickerState();
            return;
        }

        if (activePickerDelimiter == null)
        {
            return;
        }

        // Only affect the Tag Tab Tab tiles (they are in Bankmain.ITEMS)
        if (event.getActionParam1() != InterfaceID.Bankmain.ITEMS)
        {
            return;
        }

        // Only rewrite the open/view option; other options must keep full tag (they use opbase/tag internally)
        if (!VIEW_TAB_ACTION.equals(event.getOption()))
        {
            return;
        }

        final String fullRaw = Text.removeTags(event.getTarget());
        if (fullRaw == null || fullRaw.isEmpty())
        {
            return;
        }

        final String full = Text.standardize(fullRaw);
        if (full.isEmpty() || TAGTABS.equals(full))
        {
            return;
        }

        final String rel = toRelativeName(full);
        if (rel == null || rel.isEmpty())
        {
            return;
        }

        // Highlight only the subject, as requested
        event.getMenuEntry().setTarget(hi(rel));
    }

    private Plugin getActiveBankTagsPlugin()
    {
        if (bankTagsPlugin != null && pluginManager.isPluginActive(bankTagsPlugin))
        {
            return bankTagsPlugin;
        }

        for (Plugin p : pluginManager.getPlugins())
        {
            if (p != null && BANKTAGS_PLUGIN_CLASS.equals(p.getClass().getName()))
            {
                bankTagsPlugin = p;
                break;
            }
        }

        return bankTagsPlugin != null && pluginManager.isPluginActive(bankTagsPlugin) ? bankTagsPlugin : null;
    }

    private boolean isInTagTabsPicker()
    {
        final String current = configManager.getConfiguration(BANKTAGS_GROUP, BANKTAGS_ACTIVE_TAB_KEY);
        return TAGTABS.equals(current);
    }

    private static MenuEntry findEntry(MenuEntry[] entries, String option)
    {
        if (entries == null)
        {
            return null;
        }
        for (MenuEntry e : entries)
        {
            if (e != null && option.equals(e.getOption()))
            {
                return e;
            }
        }
        return null;
    }

    private static void removeMatchingEntry(Menu menu, MenuEntry marker)
    {
        for (MenuEntry e : menu.getMenuEntries())
        {
            if (e == null)
            {
                continue;
            }

            final boolean same =
                    Objects.equals(marker.getOption(), e.getOption()) &&
                            Objects.equals(marker.getTarget(), e.getTarget()) &&
                            marker.getParam0() == e.getParam0() &&
                            marker.getParam1() == e.getParam1();

            if (same)
            {
                menu.removeMenuEntry(e);
                return;
            }
        }
    }

    private List<String> loadTagTabs(boolean includeHidden)
    {
        final String csv = Objects.toString(configManager.getConfiguration(BANKTAGS_GROUP, TAG_TABS_CONFIG_KEY), "");
        final List<String> raw = Text.fromCSV(csv);

        final List<String> out = new ArrayList<>(raw.size());
        for (String t : raw)
        {
            if (t == null)
            {
                continue;
            }

            final String tag = Text.standardize(t);
            if (tag.isEmpty() || TAGTABS.equals(tag))
            {
                continue;
            }

            if (!includeHidden && isHidden(tag))
            {
                continue;
            }

            out.add(tag);
        }
        return out;
    }

    private boolean isHidden(String tag)
    {
        return Boolean.TRUE.equals(
                configManager.getConfiguration(BANKTAGS_GROUP, TAG_HIDDEN_PREFIX + Text.standardize(tag), Boolean.class)
        );
    }

    private static String sanitizeDelimiter(String d)
    {
        return (d == null || d.isEmpty()) ? "|" : d;
    }

    private static String hi(String s)
    {
        return ColorUtil.wrapWithColorTag(s, JagexColors.MENU_TARGET);
    }

    private Node buildTree(List<String> tabs, String delim)
    {
        final Node root = new Node();
        final Pattern split = Pattern.compile(Pattern.quote(delim));

        for (String fullTag : tabs)
        {
            final String[] parts = split.split(fullTag, -1);

            final List<String> segs = new ArrayList<>(parts.length);
            for (String p : parts)
            {
                if (p == null)
                {
                    continue;
                }
                final String s = p.trim();
                if (!s.isEmpty())
                {
                    segs.add(s);
                }
            }

            if (segs.isEmpty())
            {
                continue;
            }

            if (segs.size() == 1)
            {
                root.unorganizedTabs.add(fullTag);
                continue;
            }

            Node cur = root;
            for (int i = 0; i < segs.size() - 1; i++)
            {
                cur = cur.sections.computeIfAbsent(segs.get(i), k -> new Node());
            }

            final String leaf = segs.get(segs.size() - 1);
            cur.tabs.computeIfAbsent(leaf, k -> new ArrayList<>()).add(fullTag);
        }

        return root;
    }

    private void buildPlusMenu(Menu menu, Node node, String delim, String sectionPath, boolean isRoot, int p0, int p1)
    {
        // Sections first
        final List<String> sectionNames = new ArrayList<>(node.sections.keySet());
        sectionNames.sort(String.CASE_INSENSITIVE_ORDER);

        for (String sectionName : sectionNames)
        {
            final Node child = node.sections.get(sectionName);
            if (child == null)
            {
                continue;
            }

            final String childPath = sectionPath.isEmpty()
                    ? sectionName
                    : sectionPath + delim + sectionName;

            final MenuEntry sectionEntry = menu.createMenuEntry(-1)
                    .setOption("Open section")
                    .setTarget(hi(sectionName))
                    .setParam0(p0)
                    .setParam1(p1)
                    .setType(MenuAction.RUNELITE);

            // Click opens the Tag Tab Tab picker filtered to this section
            sectionEntry.onClick(e -> openFilteredPicker(childPath, false, delim));

            // Hover submenu navigation
            final Menu sub = sectionEntry.createSubMenu();
            buildPlusMenu(sub, child, delim, childPath, false, p0, p1);
        }

        // Tabs at this level
        for (Map.Entry<String, List<String>> leaf : node.tabs.entrySet())
        {
            final String leafName = leaf.getKey();
            final List<String> fullTags = leaf.getValue();
            if (fullTags == null || fullTags.isEmpty())
            {
                continue;
            }

            final boolean dup = fullTags.size() > 1;
            for (String fullTag : fullTags)
            {
                final String target = dup ? (hi(leafName) + " (" + fullTag + ")") : hi(leafName);

                menu.createMenuEntry(-1)
                        .setOption("Open tab")
                        .setTarget(target)
                        .setParam0(p0)
                        .setParam1(p1)
                        .setType(MenuAction.RUNELITE)
                        .onClick(e -> openBankTag(fullTag));
            }
        }

        // Unorganized (root only)
        if (isRoot && !node.unorganizedTabs.isEmpty())
        {
            final MenuEntry unorg = menu.createMenuEntry(-1)
                    .setOption("Open section")
                    .setTarget(hi(UNORGANIZED))
                    .setParam0(p0)
                    .setParam1(p1)
                    .setType(MenuAction.RUNELITE);

            // Click opens the Tag Tab Tab picker filtered to only non-delimited tags
            unorg.onClick(e -> openFilteredPicker("", true, delim));

            final Menu sub = unorg.createSubMenu();

            final List<String> items = new ArrayList<>(node.unorganizedTabs);
            items.sort(String.CASE_INSENSITIVE_ORDER);

            for (String tag : items)
            {
                sub.createMenuEntry(-1)
                        .setOption("Open tab " + hi(tag))
                        .setTarget("")
                        .setParam0(p0)
                        .setParam1(p1)
                        .setType(MenuAction.RUNELITE)
                        .onClick(e -> openBankTag(tag));
            }
        }
    }

    private void openBankTag(String tag)
    {
        if (tag == null || tag.isEmpty())
        {
            return;
        }

        final Plugin bankTags = getActiveBankTagsPlugin();
        if (bankTags == null)
        {
            return;
        }

        clientThread.invoke(() ->
        {
            try
            {
                final Method m = bankTags.getClass().getMethod("openBankTag", String.class);
                m.invoke(bankTags, tag);
            }
            catch (Exception ex)
            {
                log.debug("openBankTag failed: {}", tag, ex);
            }
        });
    }

    private void openFilteredPicker(String sectionPath, boolean unorganized, String delim)
    {
        final Plugin bankTags = getActiveBankTagsPlugin();
        if (bankTags == null)
        {
            return;
        }

        pendingUnorganized = unorganized;
        pendingDelimiter = delim;
        pendingTries = 0;

        pendingPrefix = unorganized ? null : Text.standardize(sectionPath);

        clientThread.invoke(() ->
        {
            try
            {
                // mirror TabInterface.handleNewTab: reset vanilla tab
                client.setVarbit(VarbitID.BANK_CURRENTTAB, 0);

                // call BankTagsPlugin.openTag("tagtabs", null, 0) via reflection
                Method openTag = null;
                for (Method m : bankTags.getClass().getMethods())
                {
                    if (!"openTag".equals(m.getName()))
                    {
                        continue;
                    }
                    final Class<?>[] p = m.getParameterTypes();
                    if (p.length == 3 && p[0] == String.class && p[2] == int.class)
                    {
                        openTag = m;
                        break;
                    }
                }

                if (openTag == null)
                {
                    log.debug("BankTagsPlugin.openTag(String, Layout, int) not found");
                    clearPending();
                    return;
                }

                openTag.invoke(bankTags, TAGTABS, null, 0);
            }
            catch (Exception ex)
            {
                log.debug("openFilteredPicker failed", ex);
                clearPending();
            }
        });
    }

    private void applyPendingPickerFilter()
    {
        if (!isInTagTabsPicker())
        {
            // User left the picker before we got a chance to apply
            clearPending();
            return;
        }

        // Retry a few times in case timing/build phases differ
        if (++pendingTries > 5)
        {
            clearPending();
            return;
        }

        final Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
        if (items == null || items.getChildren() == null)
        {
            return;
        }

        final Widget[] children = items.getChildren();

        // Identify Tag Tab Tab tiles by containing the VIEW_TAB_ACTION in their actions.
        final List<Widget> tiles = new ArrayList<>();
        for (Widget w : children)
        {
            if (w == null)
            {
                continue;
            }

            final String name = w.getName();
            if (name == null || name.isEmpty())
            {
                continue;
            }

            final String[] actions = w.getActions();
            if (actions == null || actions.length == 0)
            {
                continue;
            }

            boolean isTile = false;
            for (String a : actions)
            {
                if (VIEW_TAB_ACTION.equals(a))
                {
                    isTile = true;
                    break;
                }
            }

            if (isTile)
            {
                tiles.add(w);
            }
        }

        if (tiles.isEmpty())
        {
            return; // try again next finishbuilding
        }

        final String delim = pendingDelimiter;

        int itemX = BANK_ITEM_START_X;
        int itemY = BANK_ITEM_START_Y;
        int rowIndex = 0;
        int shown = 0;

        for (Widget w : tiles)
        {
            final String rawTag = Text.removeTags(w.getName());
            final String tag = Text.standardize(rawTag);

            boolean keep;
            if (pendingUnorganized)
            {
                keep = !TAGTABS.equals(tag) && !tag.contains(delim);
            }
            else
            {
                final String prefix = pendingPrefix + delim;
                keep = !TAGTABS.equals(tag) && tag.startsWith(prefix);
            }

            if (!keep)
            {
                w.setHidden(true);
                continue;
            }

            w.setHidden(false);
            w.setOriginalX(itemX);
            w.setOriginalY(itemY);
            w.revalidate();

            shown++;

            rowIndex++;
            if (rowIndex == BANK_ITEMS_PER_ROW)
            {
                itemX = BANK_ITEM_START_X;
                itemY += BANK_ITEM_Y_PADDING + BANK_ITEM_HEIGHT;
                rowIndex = 0;
            }
            else
            {
                itemX += BANK_ITEM_X_PADDING + BANK_ITEM_WIDTH;
            }
        }

        // If nothing matched, don't nuke the picker; just leave it as-is
        if (shown == 0)
        {
            return;
        }

        // Tighten scroll height to the compacted layout
        final int height = itemY + BANK_ITEM_HEIGHT;
        items.setScrollHeight(height);
        items.revalidate();

        // Activate “relative name” rewriting for VIEW_TAB_ACTION
        activePickerPrefix = pendingPrefix;
        activePickerUnorganized = pendingUnorganized;
        activePickerDelimiter = pendingDelimiter;

        clearPending();
    }

    private String toRelativeName(String fullStandardizedTag)
    {
        if (activePickerUnorganized)
        {
            // Only for non-delimited tags
            return fullStandardizedTag.contains(activePickerDelimiter) ? null : fullStandardizedTag;
        }

        if (activePickerPrefix == null)
        {
            return null;
        }

        final String prefix = activePickerPrefix + activePickerDelimiter;
        if (!fullStandardizedTag.startsWith(prefix))
        {
            return null;
        }

        return fullStandardizedTag.substring(prefix.length());
    }

    private void clearPending()
    {
        pendingPrefix = null;
        pendingUnorganized = false;
        pendingDelimiter = null;
        pendingTries = 0;
    }

    private void clearActivePickerState()
    {
        activePickerPrefix = null;
        activePickerUnorganized = false;
        activePickerDelimiter = null;
    }

    private static final class Node
    {
        private final Map<String, Node> sections = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final Map<String, List<String>> tabs = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final Set<String> unorganizedTabs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
    }
}
