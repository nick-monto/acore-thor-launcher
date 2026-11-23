-- Generated-by-design for mod-creature-affixes.
-- The server owns the carrier ids; the client matches the stock spell names
-- because the 3.3.5a UnitAura API does not expose a spell id reliably.
-- These are permanent dummy auras with no visual, no DB references and no
-- DO_NOT_DISPLAY bit: the client filters that bit out of UnitAura, so the
-- add-on hides their target-frame buttons instead.
CreatureAffixesData = {
    markers = {
        ["CP Test Spell 2"]  = { kind = "prefix", label = "Vicious" },
        ["Dummy Riding Aura"] = { kind = "prefix", label = "Gilded" },
        ["Mote of Anger"]     = { kind = "affix",  label = "of the Bear" },
    },
}
