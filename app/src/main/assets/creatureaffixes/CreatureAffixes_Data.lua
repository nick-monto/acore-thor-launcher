-- Generated-by-design for mod-creature-affixes.
-- The server owns the hidden carrier ids; the client matches the stock spell
-- names because the 3.3.5a UnitAura API does not expose a spell id reliably.
-- These carriers have SPELL_ATTR0_DO_NOT_DISPLAY, so they never appear in the
-- aura bar, but UnitAura can still read their names.
CreatureAffixesData = {
    markers = {
        ["Periodic Despawn Check"] = { kind = "prefix", label = "Vicious" },
        ["Racing Crop Aura"]       = { kind = "prefix", label = "Gilded" },
        ["Have Knotroot"]          = { kind = "affix",  label = "of the Bear" },
    },
}
