-- CreatureAffixes_Plates: TidyPlates nameplate names.
--
-- The 3.3.5a client has no nameplateN unit token. The native adapter publishes
-- WrathNameplateGUID, a table of the CGNamePlateFrame GUIDs in WorldFrame
-- child order. This module maps each TidyPlates plate to that order and asks
-- mod-creature-affixes for the labels that belong to that GUID over an add-on
-- whisper. Without the adapter, the old target/mouseover fallback remains.
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

local LabelsByGUID = {}
local PendingLabels = {}
local LabelRequestedAt = {}
local LabelCheckedAt = {}

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

local function IsNameplateFrame(frame)
    local region = frame:GetRegions()
    return region and region:GetObjectType() == "Texture"
        and region:GetTexture() == "Interface\\TargetingFrame\\UI-TargetingFrame-Flash"
end

-- The native adapter creates this table with the frame GUIDs in the client's
-- CGNamePlateFrame creation order. WorldFrame:GetChildren() exposes the same
-- frames in the same order to Lua, so an ordinal is enough to join the two.
local function BridgeGUIDs()
    local guids = WrathNameplateGUID
    local count = WrathNameplateGUIDCount
    if type(guids) ~= "table" or type(count) ~= "number" or count <= 0 or not WorldFrame then
        return nil
    end

    local byFrame = {}
    local ordinal = 0
    local children = { WorldFrame:GetChildren() }
    for index = 1, #children do
        local frame = children[index]
        if IsNameplateFrame(frame) then
            ordinal = ordinal + 1
            if ordinal <= count then
                byFrame[frame] = guids[ordinal]
            end
        end
    end
    -- A count mismatch means the frame order cannot be trusted; fall back for
    -- this tick rather than attach another creature's label.
    if ordinal ~= count then
        return nil
    end
    return byFrame
end

local function RequestLabels(guid)
    if not guid or guid == "" or guid == "0x0000000000000000" then
        return
    end
    if PendingLabels[guid] then
        return
    end
    local now = GetTime()
    local cached = LabelsByGUID[guid]
    if cached ~= nil then
        -- Real labels are refreshed slowly so a GM reroll reaches an already
        -- visible plate; an empty answer is retried less often.
        local age = LabelCheckedAt[guid] and now - LabelCheckedAt[guid] or 1000
        if age < (cached == false and 30 or 10) then
            return
        end
    end
    if LabelRequestedAt[guid] and now - LabelRequestedAt[guid] < 5 then
        return
    end
    PendingLabels[guid] = true
end

local function FlushLabelRequests()
    if type(SendAddonMessage) ~= "function" then
        return
    end
    local me = UnitName("player")
    if not me then
        return
    end
    local batch = {}
    for guid in pairs(PendingLabels) do
        batch[#batch + 1] = guid
        if #batch >= 8 then
            break
        end
    end
    if #batch == 0 then
        return
    end
    local now = GetTime()
    for index = 1, #batch do
        local guid = batch[index]
        PendingLabels[guid] = nil
        LabelRequestedAt[guid] = now
        LabelCheckedAt[guid] = now
    end
    SendAddonMessage("CAFF", "labels\t" .. table.concat(batch, "\t"), "WHISPER", me)
end

if type(RegisterAddonMessagePrefix) == "function" then
    RegisterAddonMessagePrefix("CAFF")
end

local channel = CreateFrame("Frame")
channel:RegisterEvent("CHAT_MSG_ADDON")
channel:SetScript("OnEvent", function(_, event, prefix, message, _, target)
    if event ~= "CHAT_MSG_ADDON" or prefix ~= "CAFF" or target ~= UnitName("player") then
        return
    end
    local guid, prefixText, affixText = message:match("^label\t([^\t]+)\t([^\t]*)\t(.*)$")
    if not guid then
        return
    end
    LabelCheckedAt[guid] = GetTime()
    if prefixText == "" and affixText == "" then
        LabelsByGUID[guid] = false
    else
        LabelsByGUID[guid] = { prefix = prefixText, affix = affixText }
    end
end)

local function ComposeForUnit(unit, plate, guid)
    if not unit or unit.type == "PLAYER" then
        return nil
    end
    if guid and UnitIsPlayer(guid) then
        return nil
    end

    local prefix, affix
    local labels = guid and LabelsByGUID[guid]
    if type(labels) == "table" then
        if labels.prefix ~= "" then
            prefix = { text = labels.prefix, kind = "prefix" }
        end
        if labels.affix ~= "" then
            affix = { text = labels.affix, kind = "affix" }
        end
    end

    -- Keep the old target/mouseover behavior when the native bridge is absent,
    -- the server has not answered yet, or this client's UnitBuff(GUID) gate
    -- does not expose a carrier.
    if not prefix and not affix then
        local token = TokenFor(unit)
        if token then
            prefix, affix = Addon:FindMarkers(token)
        end
    end
    if not prefix and not affix then
        return nil
    end

    local base = unit.name
    if not base or base == "" then
        local token = TokenFor(unit)
        base = token and UnitName(token) or nil
    end
    if not base or base == "" then
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

    local bridge = BridgeGUIDs()
    if bridge then
        for _, guid in pairs(bridge) do
            RequestLabels(guid)
        end
    end

    for plate in pairs(TidyPlates.VisibleNameplates or {}) do
        local extended = plate.extended
        local unit = extended and extended.unit
        local visual = extended and extended.visual
        if unit and visual and visual.name then
            local text = ComposeForUnit(unit, plate, bridge and bridge[plate])
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

    FlushLabelRequests()
end)
