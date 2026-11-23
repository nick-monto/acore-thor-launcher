-- CreatureAffixes: the client half of the realm's mod-creature-affixes.
--
-- The server puts hidden stock dummy auras on a modified creature. The auras
-- are sent to the client but carry SPELL_ATTR0_DO_NOT_DISPLAY, so the aura bar
-- stays clean; this add-on reads their stock spell names from UnitAura and
-- composes the configured prefix and affix into the target name.
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
    hooksecurefunc("TargetFrame_Update", UpdateName)
end

local frame = CreateFrame("Frame")
frame:RegisterEvent("PLAYER_LOGIN")
frame:RegisterEvent("PLAYER_TARGET_CHANGED")
frame:RegisterEvent("UNIT_AURA")
frame:SetScript("OnEvent", function(_, event, unit)
    if event == "UNIT_AURA" and unit ~= "target" then return end
    UpdateName()
end)
