-- CreatureAffixes: the client half of the realm's mod-creature-affixes.
--
-- The server writes the configured prefix and affix into the creature's name,
-- so the target frame, TidyPlates and the stock nameplates all read the same
-- string. The stock permanent dummy auras are still present only so this
-- add-on can hide their target/focus buttons; they carry no displayed labels.

local Data = CreatureAffixesData
if type(Data) ~= "table" or type(Data.markers) ~= "table" then
    DEFAULT_CHAT_FRAME:AddMessage("|cffff5050CreatureAffixes|r data file is missing.")
    return
end

local Markers = Data.markers

local function HideUnitIcons(unit, framePrefix)
    if not unit or UnitIsPlayer(unit) then
        return
    end
    for index = 1, 16 do
        local name = UnitBuff(unit, index)
        if name and Markers[name] then
            local button = _G[framePrefix .. "Buff" .. index]
            if button then button:Hide() end
        end
        name = UnitDebuff(unit, index)
        if name and Markers[name] then
            local button = _G[framePrefix .. "Debuff" .. index]
            if button then button:Hide() end
        end
    end
end

local function HideCarrierIcons()
    HideUnitIcons("target", "TargetFrame")
    HideUnitIcons("focus", "FocusFrame")
end

if type(hooksecurefunc) == "function" and type(TargetFrame_Update) == "function" then
    hooksecurefunc("TargetFrame_Update", HideCarrierIcons)
end

if type(hooksecurefunc) == "function" and type(TargetFrame_UpdateAuras) == "function" then
    hooksecurefunc("TargetFrame_UpdateAuras", HideCarrierIcons)
end

local frame = CreateFrame("Frame")
frame:RegisterEvent("PLAYER_LOGIN")
frame:RegisterEvent("PLAYER_TARGET_CHANGED")
frame:RegisterEvent("UNIT_AURA")
frame:SetScript("OnEvent", function(_, event, unit)
    if event == "UNIT_AURA" and unit ~= "target" and unit ~= "focus" then return end
    HideCarrierIcons()
end)
