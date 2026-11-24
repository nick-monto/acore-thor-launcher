-- CreatureAffixes_Plates: TidyPlates target and mouseover nameplate names.
--
-- Stock 3.3.5a has no C_NamePlate unit tokens. TidyPlates only resolves a GUID
-- for the target and mouseover plates (TidyPlatesCore.lua), so those are the
-- only plates that can be tied to our hidden marker auras. Other nameplates
-- keep the stock name; showing affixes on every plate would need a client
-- nameplate API or a client patch, not a database change.
--
-- This deliberately does not wrap a theme's OnUpdate: TidyPlates merges theme
-- tables by iterating TidyPlates.defaultTheme, so adding function/boolean keys
-- there breaks the next theme load. A short polling pass over the exported
-- VisibleNameplates table leaves TidyPlates' own theme data untouched.

local _, Addon = ...
if not TidyPlates or type(TidyPlates.VisibleNameplates) ~= "table" then
    return
end
if type(Addon) ~= "table" or type(Addon.FindMarkers) ~= "function" or type(Addon.ComposeName) ~= "function" then
    return
end

local function TokenFor(unit)
    if not unit or not unit.guid then
        return nil
    end
    if UnitExists("target") and UnitGUID("target") == unit.guid then
        return "target"
    end
    if UnitExists("mouseover") and UnitGUID("mouseover") == unit.guid then
        return "mouseover"
    end
    return nil
end

local function ComposeForUnit(unit)
    local token = TokenFor(unit)
    if not token or UnitIsPlayer(token) then
        return nil
    end

    local base = UnitName(token) or unit.name
    if not base or base == "" then
        return nil
    end

    local prefix, affix = Addon:FindMarkers(token)
    if not prefix and not affix then
        return nil
    end
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
                if visual.name:GetText() ~= text then
                    visual.name:SetText(text)
                end
                extended.__creatureAffixesName = true
            elseif extended.__creatureAffixesName and unit.name and visual.name:GetText() ~= unit.name then
                visual.name:SetText(unit.name)
                extended.__creatureAffixesName = nil
            end
        end
    end
end)
