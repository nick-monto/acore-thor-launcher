-- Generated-by-design for mod-creature-affixes.
-- The server owns the carrier ids; the client only needs the stock spell
-- names to keep those temporary marker auras out of the target/focus bars.
-- The custom stat and trigger auras use DO_NOT_DISPLAY; the two stock gas and
-- corruption visuals are named here so their aura buttons stay hidden too.
-- The 3.3.5a UnitAura API does not expose a spell id reliably.
CreatureAffixesData = {
    markers = {
        ["CP Test Spell 2"]   = true,
        ["Dummy Riding Aura"] = true,
        ["Mote of Anger"]     = true,
        ["Debug Debuff"]      = true,
        ["Unscathed (DND)"]   = true,
        ["Green Ghost Visual"] = true,
        ["Corruption of Time"] = true,
    },
    labels = {
        ["CP Test Spell 2"]   = { text = "Vicious",     kind = "prefix" },
        ["Dummy Riding Aura"] = { text = "Gilded",      kind = "prefix" },
        ["Mote of Anger"]     = { text = "of the Bear", kind = "affix"  },
        ["Debug Debuff"]      = { text = "Zombified",   kind = "prefix" },
        ["Unscathed (DND)"]   = { text = "of the Corruption", kind = "affix"  },
    },
}
