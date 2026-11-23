-- Generated-by-design for mod-creature-affixes.
-- The server owns the carrier ids; the client only needs the stock spell
-- names to keep those temporary marker auras out of the target/focus bars.
-- The visual/periodic effect auras on the two candidates are hidden here too;
-- their spell visuals and world effects are unchanged.
-- The 3.3.5a UnitAura API does not expose a spell id reliably.
CreatureAffixesData = {
    markers = {
        ["CP Test Spell 2"]   = true,
        ["Dummy Riding Aura"] = true,
        ["Mote of Anger"]     = true,
        ["Debug Debuff"]      = true,
        ["Unscathed (DND)"]   = true,
        ["Disease Cloud"]     = true,
        ["Corruption of Time"] = true,
    },
}
