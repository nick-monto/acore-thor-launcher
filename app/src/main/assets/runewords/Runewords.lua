-- Runewords: the client half of the realm's mod-runewords.
--
-- A word is a shape, not a list. Runes sit in the bags and a word is a connected path of them
-- over the grid the client draws, which is four columns wide and fills row by row, so a word
-- may run along a row, down a column or around a corner, and only ever inside one container.
-- The server matches that shape and casts the word's auras; this add-on draws it.
--
-- Two things the add-on cannot work out alone, and gets from the module: the 1..100 roll each
-- rune instance carries (it lives in the characters database), and therefore what the word is
-- worth. The pattern table is not one of them - Runewords_Data.lua is generated from the same
-- declarations the server's SQL is built from, so the cells highlighted here are the cells the
-- module matches.
--
-- The word's auras are stock spells, so their tooltips would show a number the roll never
-- produced. That is what this add-on exists to answer: the panel says what the word is actually
-- worth, and the aura tooltip is rewritten too where the aura reaches the buff bar at all - the
-- shipped words borrow passive spells, which the client may not show there.

local PREFIX = "RUNE"
local COLS = 4
local MAX_BAGS = 4 -- client containers: 0 is the backpack, 1..4 the equipped bags
local CONTAINER_FRAMES = 13 -- NUM_CONTAINER_FRAMES, as ContainerFrame.lua declares it

local GOLD, GREEN, GREY, RED = "|cffffd200", "|cff40c040", "|cff808080", "|cffff5050"

local defaults = { enabled = true, panel = true, highlight = true, debug = false }

RunewordsDB = RunewordsDB or {}

local L = {
    TITLE = "Runewords",
    PENDING = "rolls pending",
    SERVER_MISSING = "the server has no mod-runewords: runes are worth nothing here.",
    SERVER_OK = "server module found",
    RUNE = "rune %d of %d",
    ROLL = "roll: %d%%",
    ROLL_UNKNOWN = "roll: ?",
    USES = "used by: %s",
    NO_WORD_USES = "no word uses this rune",
    ON = "on",
    OFF = "off",
    ENABLED = "enabled",
    DISABLED = "disabled",
    PANEL = "panel: %s",
    HIGHLIGHT = "highlight: %s",
    DEBUG = "debug: %s",
    SYNCING = "asking the server for the rolls",
    USAGE = "/rw on | off | panel | highlight | sync | debug",
}

if GetLocale() == "ruRU" then
    L = {
        TITLE = "Руны",
        PENDING = "броски неизвестны",
        SERVER_MISSING = "на сервере нет mod-runewords: руны здесь ничего не дают.",
        SERVER_OK = "серверный модуль найден",
        RUNE = "руна %d из %d",
        ROLL = "бросок: %d%%",
        ROLL_UNKNOWN = "бросок: ?",
        USES = "входит в: %s",
        NO_WORD_USES = "эта руна ни в одно слово не входит",
        ON = "вкл",
        OFF = "выкл",
        ENABLED = "включён",
        DISABLED = "выключен",
        PANEL = "панель: %s",
        HIGHLIGHT = "подсветка: %s",
        DEBUG = "отладка: %s",
        SYNCING = "запрашиваю броски у сервера",
        USAGE = "/rw on | off | panel | highlight | sync | debug",
    }
end

local function Print(msg)
    DEFAULT_CHAT_FRAME:AddMessage("|cff33ff99Runewords:|r " .. msg)
end

local function OnOff(value)
    return value and L.ON or L.OFF
end

local Data = RunewordsData
if type(Data) ~= "table" then
    Print("Runewords_Data.lua is missing: reinstall the add-on.")
    return
end

local runeData, wordData = Data.runes, Data.words
local wordsByRune = {}
local runeCount = 0
for entry in pairs(runeData) do runeCount = runeCount + 1 end
for _, word in ipairs(wordData) do
    for _, cell in ipairs(word.cells) do
        wordsByRune[cell[3]] = wordsByRune[cell[3]] or {}
        tinsert(wordsByRune[cell[3]], word)
    end
end

-- ---------------------------------------------------------------------------
-- What the bags hold, and the shape they draw
-- ---------------------------------------------------------------------------

-- Rolls as the module reported them: rolls[bag][slot] = { roll, entry }. The entry travels with
-- the roll so a bag that moved under the reply is noticed instead of trusted.
local rolls = {}

-- The sync round trip: when the last one was asked for and when one arrived. A realm without
-- the module never answers, and saying so once is better than a panel stuck on "pending".
local askedAt, answeredAt, announcedMissing

local function Send(what)
    SendAddonMessage(PREFIX, what, "WHISPER", UnitName("player"))
end

local function Sync()
    if not RunewordsDB.enabled then return end
    rolls = {}
    askedAt, answeredAt = GetTime(), nil
    Send("sync")
end

-- The entry of an item in a container slot, or nil. The client's slots are 1-based here, the
-- same numbering ContainerFrame_Update and GetContainerItemLink take; a pattern's cell is the
-- slot minus one, which is the module's own zero-based count.
local function SlotEntry(bag, slot)
    local link = GetContainerItemLink(bag, slot)
    if not link then return nil end
    return tonumber(link:match("item:(%d+)"))
end

-- The roll of one grid cell, or nil while it is unknown or the slot holds something else now.
-- The module reports grid indices, so the client slot to check is that index plus one.
local function RollAt(bag, index)
    local known = rolls[bag] and rolls[bag][index]
    if not known then return nil end
    if SlotEntry(bag, index + 1) ~= known[2] then return nil end
    return known[1]
end

-- The rune cells of one container: { [grid index] = entry }, plus the slot count, where the
-- index is zero-based because that is what a pattern is written in and what the module sends.
local function ContainerRunes(bag)
    local size = GetContainerNumSlots(bag) or 0
    if size < 1 then return nil end

    local cells, found = {}, false
    for slot = 1, size do
        local entry = SlotEntry(bag, slot)
        if entry and runeData[entry] then
            cells[slot - 1] = entry
            found = true
        end
    end
    return found and cells or nil, size
end

-- Every placement of one word in one container, each as a list of cell indices. A word can
-- fit the same grid in more than one place, and the server takes the strongest placement, so
-- the client has to consider all of them or it would disagree with the buff it describes.
--
-- The grid is four wide, so a flat index alone does not describe a shape: index 3 is the right
-- edge of a row and 4 the left edge of the next, and a pattern running between them would form
-- a word nobody drew. The anchor fixes the origin's column and every cell stays inside it.
local function Placements(word, cells, size)
    local out = {}
    for slot in pairs(cells) do
        for _, cell in ipairs(word.cells) do
            if cells[slot] == cell[3] then
                local originCol = slot % COLS - cell[2]
                local origin = slot - (cell[1] * COLS + cell[2])
                if originCol >= 0 and origin >= 0 then
                    local slots, fits = {}, true
                    for _, expected in ipairs(word.cells) do
                        local index = origin + expected[1] * COLS + expected[2]
                        if index < 0 or index >= size or originCol + expected[2] >= COLS
                            or cells[index] ~= expected[3] then
                            fits = false
                            break
                        end
                        slots[#slots + 1] = index
                    end
                    if fits then out[#out + 1] = slots end
                end
            end
        end
    end
    return out
end

-- A word is as strong as its weakest rune, floored by the word's own minimum. Unknown rolls
-- are not guessed at: the word is still named, but the number is withheld.
local function Strength(word, bag, slots)
    local known, weakest = true, 100
    for _, slot in ipairs(slots) do
        local roll = RollAt(bag, slot)
        if roll then
            if roll < weakest then weakest = roll end
        else
            known = false
        end
    end
    return math.max(word.minPct, weakest), known
end

-- What the bags currently hold. Mirrors the module's rule exactly: only a word the character
-- has the level for, the longest wins, then the lowest id.
local function Resolve()
    local level = UnitLevel("player") or 0
    local best, bestBag, bestCells, bestSize

    for bag = 0, MAX_BAGS do
        local cells, size = ContainerRunes(bag)
        if cells then
            for _, word in ipairs(wordData) do
                if level >= word.level and #Placements(word, cells, size) > 0 then
                    if not best
                        or #word.cells > #best.cells
                        or (#word.cells == #best.cells and word.id < best.id) then
                        best, bestBag, bestCells, bestSize = word, bag, cells, size
                    end
                end
            end
        end
    end

    if not best then return nil end

    local pct, known, slots
    for _, placement in ipairs(Placements(best, bestCells, bestSize)) do
        local value, complete = Strength(best, bestBag, placement)
        if not slots or (complete and not known) or (complete == known and value > pct) then
            pct, known, slots = value, complete, placement
        end
    end

    -- Slots are marked as "bag:slot" so the highlighter never has to work out which container a
    -- button belongs to.
    local marked = {}
    for _, slot in ipairs(slots) do
        marked[bestBag .. ":" .. slot] = true
    end

    return { word = best, bag = bestBag, pct = pct, known = known, slots = marked }
end

-- What the word is actually worth, as a percentage of the stock spell's own value: the word's
-- scale times the weakest rune's roll, the same arithmetic the module feeds to
-- SpellEffectInfo::CalcValue. With scale 100 - every word in the shipped table - it is simply
-- the roll, so 87% means the aura is at 87% of the amount the spell itself carries.
local function Worth(word, pct)
    return math.floor(word.scale * pct / 100 + 0.5)
end

-- ---------------------------------------------------------------------------
-- The panel
-- ---------------------------------------------------------------------------

local panel = CreateFrame("Frame", "RunewordsPanel", UIParent)
panel:SetSize(200, 46)
panel:SetPoint("CENTER", 0, -190)
panel:SetBackdrop({
    bgFile = "Interface\\Tooltips\\UI-Tooltip-Background",
    edgeFile = "Interface\\Tooltips\\UI-Tooltip-Border",
    tile = true, tileSize = 16, edgeSize = 12,
    insets = { left = 3, right = 3, top = 3, bottom = 3 },
})
panel:SetBackdropColor(0, 0, 0, 0.6)
panel:SetBackdropBorderColor(0.4, 0.4, 0.4, 0.9)
panel:SetMovable(true)
panel:EnableMouse(true)
panel:RegisterForDrag("LeftButton")
panel:SetScript("OnDragStart", panel.StartMoving)
panel:SetScript("OnDragStop", function(self)
    self:StopMovingOrSizing()
    local point, _, _, x, y = self:GetPoint()
    RunewordsDB.point, RunewordsDB.x, RunewordsDB.y = point, x, y
end)

local title = panel:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
title:SetPoint("TOPLEFT", 8, -6)
title:SetText(L.TITLE)
title:SetTextColor(0.6, 0.6, 0.6)

local wordLabel = panel:CreateFontString(nil, "OVERLAY", "GameFontNormal")
wordLabel:SetPoint("TOPLEFT", 8, -20)

local valueLabel = panel:CreateFontString(nil, "OVERLAY", "GameFontHighlightSmall")
valueLabel:SetPoint("TOPRIGHT", -8, -22)

local hintLabel = panel:CreateFontString(nil, "OVERLAY", "GameFontDisableSmall")
hintLabel:SetPoint("BOTTOMLEFT", 8, 6)

local applied

local function Refresh()
    if not RunewordsDB.enabled or (UnitLevel("player") or 0) < 1 then
        applied = nil
        panel:Hide()
        return
    end

    applied = Resolve()
    if not applied then
        panel:Hide()
        return
    end

    wordLabel:SetText(GOLD .. applied.word.name .. "|r")
    if applied.known then
        local worth = Worth(applied.word, applied.pct)
        valueLabel:SetText((worth >= 100 and GREEN or RED) .. format("%d%%", worth) .. "|r")
    else
        valueLabel:SetText(GREY .. L.PENDING .. "|r")
    end

    local names = {}
    for _, cell in ipairs(applied.word.cells) do
        names[#names + 1] = runeData[cell[3]] and runeData[cell[3]].name or "?"
    end
    hintLabel:SetText(GREY .. table.concat(names, " ") .. "|r")

    if RunewordsDB.panel then
        panel:Show()
    end
end

-- ---------------------------------------------------------------------------
-- The bag highlight
-- ---------------------------------------------------------------------------

-- One border per item button, made the first time that button shows a rune. It is a child of
-- the button, so the client moves and hides it with the slot it belongs to.
local function Border(button)
    local border = button.runewordBorder
    if not border then
        border = CreateFrame("Frame", nil, button)
        border:SetPoint("TOPLEFT", 1, -1)
        border:SetPoint("BOTTOMRIGHT", -1, 1)
        border:SetFrameLevel(button:GetFrameLevel() + 2)
        border:SetBackdrop({
            edgeFile = "Interface\\Tooltips\\UI-Tooltip-Border",
            edgeSize = 10,
            insets = { left = 1, right = 1, top = 1, bottom = 1 },
        })
        border:SetBackdropBorderColor(0, 0, 0, 0)
        button.runewordBorder = border
    end
    return border
end

-- FrameXML names the buttons "<frame>Item"..1 to frame.size and gives button i the id
-- size - i + 1, so a button's id is the container slot it shows (1-based) and its position in
-- the frame is only a drawing order.
local function Decorate(frame)
    local name, bag = frame:GetName(), frame:GetID()
    if not name or not bag or not frame.size then return end

    for i = 1, frame.size do
        local button = _G[name .. "Item" .. i]
        if button then
            -- The button's own id, not its position in the frame: FrameXML numbers the buttons
            -- bottom-up and gives each one the slot it shows, so this is the slot.
            local slot = button:GetID()
            local entry = slot and SlotEntry(bag, slot)
            local border = button.runewordBorder

            -- The tooltip reads these two, so the bag/slot numbering is settled once, here.
            button.runewordBag, button.runewordSlot = bag, slot

            if entry and runeData[entry] then
                border = Border(button)
                if applied and applied.slots[bag .. ":" .. (slot - 1)] then
                    border:SetBackdropBorderColor(1, 0.82, 0, 0.9) -- part of the word
                else
                    border:SetBackdropBorderColor(0.3, 0.6, 1, 0.5) -- a rune on its own
                end
                border:Show()
            elseif border then
                border:Hide()
            end
        end
    end
end

local function DecorateAll()
    for i = 1, CONTAINER_FRAMES do
        local frame = _G["ContainerFrame" .. i]
        if frame and frame:IsShown() then Decorate(frame) end
    end
end

-- The client redraws a container from one function, so hooking it keeps the borders right
-- through a swap, a loot, a sort or a relog without polling every bag. Refresh first: the
-- highlight has to describe the bags as they are now, not as they were a second ago.
hooksecurefunc("ContainerFrame_Update", function(frame)
    if not RunewordsDB.enabled or not RunewordsDB.highlight then return end
    Refresh()
    Decorate(frame)
end)

-- ---------------------------------------------------------------------------
-- Tooltips: the rune, and the buff it feeds
-- ---------------------------------------------------------------------------

GameTooltip:HookScript("OnTooltipSetItem", function(self)
    local owner = self:GetOwner()
    local bag, slot = owner and owner.runewordBag, owner and owner.runewordSlot
    if not bag or not slot then return end

    local entry = SlotEntry(bag, slot)
    local rune = entry and runeData[entry]
    if not rune then return end

    self:AddLine(" ")
    self:AddLine(format(L.RUNE, rune.step, runeCount), 1, 0.82, 0, true)

    local roll = RollAt(bag, slot - 1)
    if roll then
        self:AddLine(format(L.ROLL, roll), 0.25, 0.75, 0.25, true)
    else
        self:AddLine(L.ROLL_UNKNOWN, 0.5, 0.5, 0.5, true)
    end

    local users = wordsByRune[entry]
    if users then
        local names = {}
        for _, word in ipairs(users) do
            names[#names + 1] = word.name
            -- The ladder level is worth knowing: a word the character cannot use yet is not a
            -- word it can form, and the server enforces exactly that.
        end
        self:AddLine(format(L.USES, table.concat(names, ", ")), 0.8, 0.8, 0.8, true)
    else
        self:AddLine(L.NO_WORD_USES, 0.5, 0.5, 0.5, true)
    end
    self:Show()
end)

-- The word's auras are stock spells, so their tooltip is stock text. Replace the number, but
-- only for a spell of the word that is actually up: the same stock spell can back several
-- words, and only one is applied.
local function AppendWordLine(tooltip)
    if not applied or not applied.known then return end

    local name = tooltip:GetName()
    local first = name and _G[name .. "TextLeft1"]
    local text = first and first:GetText()
    if not text then return end

    for _, spell in ipairs(applied.word.spells) do
        if spell ~= 0 and GetSpellInfo(spell) == strtrim(text) then
            tooltip:AddLine(" ")
            tooltip:AddLine(GOLD .. applied.word.name .. "|r  " ..
                GREEN .. format("%d%%", Worth(applied.word, applied.pct)) .. "|r", 1, 1, 1, true)
            return
        end
    end
end

for _, method in ipairs({ "SetUnitBuff", "SetUnitAura" }) do
    local original = GameTooltip[method]
    if original then
        GameTooltip[method] = function(self, ...)
            local result = original(self, ...)
            AppendWordLine(self)
            return result
        end
    end
end

-- ---------------------------------------------------------------------------
-- Events
-- ---------------------------------------------------------------------------

local frame = CreateFrame("Frame")
local syncAt

frame:RegisterEvent("PLAYER_LOGIN")
frame:RegisterEvent("PLAYER_ENTERING_WORLD")
frame:RegisterEvent("BAG_UPDATE")
frame:RegisterEvent("CHAT_MSG_ADDON")
frame:RegisterEvent("UNIT_INVENTORY_CHANGED")

-- One sync answers everything: a roll never changes once assigned, so a request after a bag
-- change is the whole conversation. Debounced, because a loot or a sort fires BAG_UPDATE once
-- per item moved.
local function QueueSync(delay)
    if RunewordsDB.enabled then syncAt = GetTime() + (delay or 0.4) end
end

frame:SetScript("OnUpdate", function()
    local now = GetTime()
    if syncAt and now >= syncAt then
        syncAt = nil
        Sync()
    end
    -- No answer at all: the realm has no module, or it was switched off. Say it once; a word
    -- can still be recognised, it just is not worth anything.
    if askedAt and not answeredAt and now - askedAt > 6 and not announcedMissing then
        announcedMissing = true
        askedAt = nil
        Print(L.SERVER_MISSING)
    end
end)

frame:SetScript("OnEvent", function(self, event, arg1, arg2, _, arg4)
    if event == "PLAYER_LOGIN" then
        for k, v in pairs(defaults) do
            if RunewordsDB[k] == nil then RunewordsDB[k] = v end
        end
        panel:ClearAllPoints()
        panel:SetPoint(RunewordsDB.point or "CENTER", UIParent, RunewordsDB.point or "CENTER",
            RunewordsDB.x or 0, RunewordsDB.y or -190)
        if RegisterAddonMessagePrefix then RegisterAddonMessagePrefix(PREFIX) end
    elseif event == "PLAYER_ENTERING_WORLD" then
        QueueSync(1)
    elseif event == "UNIT_INVENTORY_CHANGED" then
        if arg1 ~= "player" then return end
        QueueSync()
    elseif event == "BAG_UPDATE" then
        QueueSync()
    elseif event == "CHAT_MSG_ADDON" then
        if arg1 ~= PREFIX or arg4 ~= UnitName("player") then return end
        if strsub(arg2 or "", 1, 5) ~= "slots" then return end
        if RunewordsDB.debug then Print("server: " .. arg2) end
        for bag, slot, roll, entry in string.gmatch(arg2, "(%d+):(%d+):(%d+):(%d+)") do
            bag, slot = tonumber(bag), tonumber(slot)
            rolls[bag] = rolls[bag] or {}
            rolls[bag][slot] = { tonumber(roll), tonumber(entry) }
        end
        answeredAt = GetTime()
        if announcedMissing then
            announcedMissing = nil
            if RunewordsDB.debug then Print(L.SERVER_OK) end
        end
    end

    Refresh()
    DecorateAll()
end)

-- ---------------------------------------------------------------------------
-- Commands
-- ---------------------------------------------------------------------------

SLASH_RUNEWORDS1 = "/rw"
SLASH_RUNEWORDS2 = "/runewords"
SlashCmdList.RUNEWORDS = function(msg)
    local cmd = strlower(strtrim(msg or ""))

    if cmd == "on" or cmd == "off" then
        RunewordsDB.enabled = (cmd == "on")
        Print(RunewordsDB.enabled and L.ENABLED or L.DISABLED)
        if RunewordsDB.enabled then QueueSync(0) end
    elseif cmd == "panel" then
        RunewordsDB.panel = not RunewordsDB.panel
        Print(format(L.PANEL, OnOff(RunewordsDB.panel)))
    elseif cmd == "highlight" then
        RunewordsDB.highlight = not RunewordsDB.highlight
        Print(format(L.HIGHLIGHT, OnOff(RunewordsDB.highlight)))
    elseif cmd == "sync" then
        Sync()
        Print(L.SYNCING)
    elseif cmd == "debug" then
        RunewordsDB.debug = not RunewordsDB.debug
        Print(format(L.DEBUG, OnOff(RunewordsDB.debug)))
    else
        Print(L.USAGE)
    end

    Refresh()
    DecorateAll()
end
