package net.runelite.client.plugins.banktagnavigator;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(BankTagNavigatorConfig.GROUP)
public interface BankTagNavigatorConfig extends Config
{
    String GROUP = "banktagnavigator";

    @ConfigItem(
            keyName = "delimiter",
            name = "Section delimiter",
            description = "Delimiter used to split a tag into sections (eg: PvM|Bossing|K'ril)."
    )
    default String delimiter()
    {
        return "|";
    }

    @ConfigItem(
            keyName = "hideDefaultViewTagTabs",
            name = "Hide default 'View tag tabs'",
            description = "If enabled, removes the original 'View tag tabs' entry from the + widget menu."
    )
    default boolean hideDefaultViewTagTabs()
    {
        return false;
    }

    @ConfigItem(
            keyName = "showHiddenTags",
            name = "Show hidden tags",
            description = "If enabled, includes tags marked hidden by the Bank Tags plugin."
    )
    default boolean showHiddenTags()
    {
        return false;
    }
}