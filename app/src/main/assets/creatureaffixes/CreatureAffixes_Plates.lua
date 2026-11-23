-- CreatureAffixes_Plates: TidyPlates target/mouseover nameplate names.
--
-- Stock 3.3.5a has no C_NamePlate unit tokens. TidyPlates only resolves a GUID
-- for the target and mouseover plates (TidyPlatesCore.lua), so those are the
-- only plates that can be tied to our marker auras. Other nameplates
-- keep the stock name; showing affixes on every plate would need a client
-- nameplate API or a client patch, not a database change.
--
-- This deliberately does not wrap a theme's OnUpdate: TidyPlates merges theme
-- tables by iterating TidyPlates.defaultTheme, so adding function/boolean keys
-- there breaks the next theme load. A short polling pass over the exported
-- VisibleNameplates table leaves TidyPlates' own theme data untouched.

local _, Addon = ...
if not TidyPlates then
    return
end

local function TokenFor(unit)
    if not unit or not unit.guid then
        return nil
    end
    if UnitGUID("target") == unit.guid then
        return "target"
    end
    if UnitGUID("mouseover") == unit.guid then
        return "mouseover"
    end
    return nil
end

local function ComposeForUnit(unit)
    local token = TokenFor(unit)
    if not token or UnitIsPlayer(token) then
        return nil
    end

    local base = UnitName(token)
    if not base then
        return nil
    end

    local prefix, affix = Addon:FindMarkers(token)
    return Addon:ComposeName(base, prefix, affix)
end

local ticker = CreateFrame("Frame")
ticker.elapsed = 0
ticker:SetScript("OnUpdate", function(self, elapsed)
    self.elapsed = self.elapsed + elapsed
    if self.elapsed < 0.1 then
        return
    end
    self.elapsed = 0

    for plate in pairs(TidyPlates.VisibleNameplates or {}) do
        local extended = plate.extended
        local unit = extended and extended.unit
        local visual = extended and extended.visual
        if unit and visual and visual.name then
            local text = ComposeForUnit(unit)
            if text then
                visual.name:SetText(text)
                extended.__creatureAffixesName = true
            elseif extended.__creatureAffixesName and unit.name then
                visual.name:SetText(unit.name)
                extended.__creatureAffixesName = nil
            end
        end
    end
end)
