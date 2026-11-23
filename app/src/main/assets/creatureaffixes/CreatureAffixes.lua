-- CreatureAffixes: the client half of the realm's mod-creature-affixes.
--
-- The server puts stock permanent dummy auras on a modified creature. They
-- carry no visuals and no DO_NOT_DISPLAY bit, because the client filters that
-- bit out of UnitAura; this add-on reads their names from UnitAura, composes
-- the configured prefix and affix into the target name, and hides the marker's
-- buttons on the target frame.
--
-- The hidden size layer has no aura, no marker and no client data.

local _, Addon = ...
local Data = CreatureAffixesData
if type(Data) ~= "table" or type(Data.markers) ~= "table" then
    DEFAULT_CHAT_FRAME:AddMessage("|cffff5050CreatureAffixes|r data file is missing.")
    return
end

local Markers = Data.markers
local NameFrame

local function GetNameFrame()
    if not NameFrame then
        NameFrame = _G["TargetFrameTextureFrameName"] or _G["TargetFrameName"]
    end
    return NameFrame
end

function Addon:FindMarkers(unit)
    if not unit or UnitIsPlayer(unit) then
        return nil, nil
    end

    local prefix, affix
    local function Consider(name)
        local marker = name and Markers[name]
        if not marker then return end
        if marker.kind == "prefix" then
            prefix = marker
        else
            affix = marker
        end
    end

    for index = 1, 40 do
        Consider(UnitBuff(unit, index))
        Consider(UnitDebuff(unit, index))
        Consider(UnitAura(unit, index))
    end
    return prefix, affix
end

function Addon:ComposeName(base, prefix, affix)
    local text = base
    if prefix then
        text = prefix.label .. " " .. text
    end
    if affix then
        text = text .. " " .. affix.label
    end
    return text
end

-- The marker auras are client-visible so UnitAura can return their names;
-- hiding their target/focus buttons keeps them out of the aura bar anyway.
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

local function UpdateName()
    local frame = GetNameFrame()
    local base = UnitName("target")
    if not frame or not base then
        return
    end

    local prefix, affix = Addon:FindMarkers("target")
    frame:SetText(Addon:ComposeName(base, prefix, affix))
end

if type(hooksecurefunc) == "function" and type(TargetFrame_Update) == "function" then
    hooksecurefunc("TargetFrame_Update", function()
        UpdateName()
        HideCarrierIcons()
    end)
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
    UpdateName()
    HideCarrierIcons()
end)
