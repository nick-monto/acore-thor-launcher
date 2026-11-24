-- CreatureAffixes: the client half of the realm's mod-creature-affixes.
--
-- The server composes the creature's server-side name, but 3.3.5a clients
-- resolve creature names from entry-keyed query data, so a live SetName does
-- not change the target frame. This add-on composes the displayed target and
-- focus names from the carrier auras instead, and hides those carrier buttons.
-- CreatureAffixes_Plates.lua uses the same marker lookup on every TidyPlates
-- plate through the native adapter's GUID bridge, falling back to target and
-- mouseover when the adapter is absent.

local _, Addon = ...
if type(Addon) ~= "table" then Addon = {} end

local Data = CreatureAffixesData
if type(Data) ~= "table" or type(Data.markers) ~= "table" then
    DEFAULT_CHAT_FRAME:AddMessage("|cffff5050CreatureAffixes|r data file is missing.")
    return
end

local Markers = Data.markers
local Labels = type(Data.labels) == "table" and Data.labels or {}

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

local function ConsiderMarker(state, name)
    local label = name and Labels[name]
    if not label then return end
    if label.kind == "prefix" then
        state.prefix = label
    else
        state.affix = label
    end
end

function Addon:FindMarkers(unit)
    if not unit or UnitIsPlayer(unit) then
        return nil, nil
    end
    local state = {}
    for index = 1, 40 do
        ConsiderMarker(state, UnitBuff(unit, index))
        ConsiderMarker(state, UnitDebuff(unit, index))
    end
    return state.prefix, state.affix
end

function Addon:ComposeName(base, prefix, affix)
    if not base or base == "" then
        return nil
    end
    return (prefix and prefix.text .. " " or "") .. base .. (affix and " " .. affix.text or "")
end

local function ComposeName(unit)
    local prefix, affix = Addon:FindMarkers(unit)
    return Addon:ComposeName(UnitName(unit), prefix, affix)
end

local function UpdateHeaderName(unit, fontString)
    if not fontString or not UnitExists(unit) then return end
    local name = ComposeName(unit)
    if name then fontString:SetText(name) end
end

local function UpdateHeaderNames()
    UpdateHeaderName("target", TargetFrameTextureFrameName)
    UpdateHeaderName("focus", FocusFrameTextureFrameName)
end

if type(hooksecurefunc) == "function" and type(TargetFrame_Update) == "function" then
    hooksecurefunc("TargetFrame_Update", function()
        HideCarrierIcons()
        UpdateHeaderNames()
    end)
end

if type(hooksecurefunc) == "function" and type(TargetFrame_UpdateAuras) == "function" then
    hooksecurefunc("TargetFrame_UpdateAuras", function()
        HideCarrierIcons()
        UpdateHeaderNames()
    end)
end

if type(hooksecurefunc) == "function" and type(FocusFrame_Update) == "function" then
    hooksecurefunc("FocusFrame_Update", UpdateHeaderNames)
end

local frame = CreateFrame("Frame")
frame:RegisterEvent("PLAYER_LOGIN")
frame:RegisterEvent("PLAYER_TARGET_CHANGED")
frame:RegisterEvent("PLAYER_FOCUS_CHANGED")
frame:RegisterEvent("UNIT_AURA")
frame:SetScript("OnEvent", function(_, event, unit)
    if event == "UNIT_AURA" and unit ~= "target" and unit ~= "focus" then return end
    HideCarrierIcons()
    UpdateHeaderNames()
end)
