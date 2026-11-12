-- Runewords: the client half of the realm's mod-runewords.
--
-- A word is a line, not a list. Runes sit in the bags and a word is a straight run of them
-- across a row or down a column of the grid the client draws, and only ever inside one
-- container. The line has to be a maximal run - a rune past either end of it means the player
-- drew something longer - and one rune may stand in a word across and in a word down at the same
-- time, which is how two words are meant to be crossed. The server matches the lines and casts
-- the words' auras; this add-on draws them.
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
local ORANGE = "|cffff9a2e"

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
    WORD_RULE = "the average of the rolls sets the strength",
    VALUE = "vendor value: %s  (base %s)",
    VALUE_UNKNOWN = "vendor value: %s",
    ON = "on",
    OFF = "off",
    ENABLED = "enabled",
    DISABLED = "disabled",
    PANEL = "panel: %s",
    HIGHLIGHT = "highlight: %s",
    DEBUG = "debug: %s",
    HELD = "%d held",
    HELD_MORE = "%d held, %d shown",
    SYNCING = "asking the server for the rolls",
    USAGE = "/rw on | off | panel | highlight | sync | debug",
}

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
    -- The axis is in the cells themselves: across shares a row, down shares a column. Read once
    -- here, so the matcher, the highlighter and the diagram cannot read it differently.
    word.horizontal = #word.cells < 2 or word.cells[1][1] == word.cells[2][1]
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

-- The slot the client draws at a cell, with row 0 the top row and column 0 the left column.
-- Measured from the container buttons themselves: a bag fills from the bottom right, leftwards
-- and upwards, so a container whose slot count is not a multiple of four keeps its short row at
-- the top, right-aligned, and (rows - 1 - row) * COLS + (COLS - 1 - col) is the whole layout.
-- Nil is a cell the container does not have - the top left of a short row.
local function SlotAt(size, row, col)
    local rows = math.ceil(size / COLS)
    if row < 0 or col < 0 or row >= rows or col >= COLS then return nil end
    return (rows - 1 - row) * COLS + (COLS - 1 - col)
end

-- The cells one word occupies when it starts at (row, col), in reading order, or nil when it does
-- not sit there. Anything of the same row or column just past either end rules the placement out:
-- a word is a maximal run, so a line longer than the word is a line drawing something else.
local function LineAt(word, cells, size, row, col)
    local horizontal = word.horizontal
    local slots = {}
    for position = 1, #word.cells do
        local index
        if horizontal then
            index = SlotAt(size, row, col + position - 1)
        else
            index = SlotAt(size, row + position - 1, col)
        end
        if not index or cells[index] ~= word.cells[position][3] then return nil end
        slots[position] = index
    end

    local before, after
    if horizontal then
        before, after = SlotAt(size, row, col - 1), SlotAt(size, row, col + #word.cells)
    else
        before, after = SlotAt(size, row - 1, col), SlotAt(size, row + #word.cells, col)
    end
    if (before and cells[before]) or (after and cells[after]) then return nil end

    return slots
end

-- Every place one word sits in one container, each as the list of cell indices it occupies. A
-- word is short enough to have several, and the server takes the strongest, so the client has to
-- consider all of them or it would disagree with the buff it describes. Nothing here consumes a
-- cell: one rune can be the crossing of a word across and a word down, and both form.
local function Placements(word, cells, size)
    local rows = math.ceil(size / COLS)
    local along = word.horizontal and COLS or rows
    local across = word.horizontal and rows or COLS
    local out = {}
    for line = 0, across - 1 do
        for start = 0, along - #word.cells do
            local slots
            if word.horizontal then
                slots = LineAt(word, cells, size, line, start)
            else
                slots = LineAt(word, cells, size, start, line)
            end
            if slots then out[#out + 1] = slots end
        end
    end
    return out
end

-- A word is worth the average of its rune rolls, rounded half up, and nothing else - the same rule
-- the module applies, so the panel cannot disagree with the aura. Unknown rolls are not guessed at:
-- the word is still named, but the number is withheld.
local function Strength(word, bag, slots)
    local known, sum = true, 0
    for _, slot in ipairs(slots) do
        local roll = RollAt(bag, slot)
        if roll then sum = sum + roll else known = false end
    end
    local count = #slots
    return math.floor((sum + count / 2) / count), known
end

-- Every word the bags currently form. Mirrors the module's rule exactly: a word counts only
-- above its level gate, one entry per word id (the strongest placement), and two copies of one
-- word in different bags are the same word rather than twice the power.
local function ResolveAll()
    local level = UnitLevel("player") or 0
    local best = {}

    for bag = 0, MAX_BAGS do
        local cells, size = ContainerRunes(bag)
        if cells then
            for _, word in ipairs(wordData) do
                if level >= word.level then
                    local placements = Placements(word, cells, size)
                    if #placements > 0 then
                        local pct, known, slots
                        for _, placement in ipairs(placements) do
                            local value, complete = Strength(word, bag, placement)
                            if not slots or (complete and not known)
                                or (complete == known and value > pct) then
                                pct, known, slots = value, complete, placement
                            end
                        end
                        if not best[word.id] or pct > best[word.id].pct then
                            -- Slots are marked as "bag:slot" so the highlighter never has to work
                            -- out which container a button belongs to.
                            local marked = {}
                            for _, slot in ipairs(slots) do
                                marked[bag .. ":" .. slot] = true
                            end
                            best[word.id] = { word = word, bag = bag, pct = pct, known = known,
                                              slots = marked }
                        end
                    end
                end
            end
        end
    end

    local out = {}
    for _, entry in pairs(best) do out[#out + 1] = entry end
    -- Ascending id, so two words that share a stock spell always read in the same order.
    table.sort(out, function(a, b) return a.word.id < b.word.id end)
    return out
end

-- What the word is actually worth, as a percentage of the stock spell's own value: the word's
-- scale times the average roll. The same arithmetic the module feeds to
-- SpellEffectInfo::CalcValue. With scale 100 - every word in the shipped table - it is simply
-- the average, so 87% means the aura is at 87% of the amount the spell itself carries.
local function Worth(word, pct)
    return math.floor(word.scale * pct / 100 + 0.5)
end

-- ---------------------------------------------------------------------------
-- The panel
-- ---------------------------------------------------------------------------

local PANEL_WIDTH = 480
local PAD_X = 16            -- content inset from the page edge
local SHAPE_PITCH = 7       -- one dot per bag cell, six units square on a seven-unit pitch
-- The word and its effects, clear of the diagram and its rune list. Wide enough for the longest
-- line the table has: six runes, dots between them, and no ellipsis to hide what the word is made
-- of - the runes are the one thing a player has to be able to read off this page.
local TEXT_X = PAD_X + 152
local TITLE = 26            -- the seal and the page's own heading
local ROW_TOP = TITLE + 12
local ROW_HEIGHT = 33       -- two lines: the word, then what it grants
local BOTTOM = 12           -- a little paper below the last entry
local MAX_ROWS = 7

-- The panel is a page, not a tooltip: the client's own achievement-journal parchment, ruled the
-- way the achievement list rules its page, and written in ink. Nothing else is drawn up there -
-- the journal's header strip is a busy metal band that competes with the words, and the page
-- reads better with the seal, the heading and the count alone.
--
-- The panel's inks are read over a black outline (the `OUTLINE` flag on every face it sets text
-- in), so they are light: an unoutlined light ink would wash into the parchment, and an unoutlined
-- dark one is the muddy half of the same problem. The words are the reason the panel exists.
local INK = {
    soft = "|cffe8d5aa",  -- an effect's label and its number: a shade under the names' white
    proc = "|cffffa03a",  -- a proc's name, which is not scaled by the roll
}

-- A rune's name wears its item quality, the same five colours an item link uses, and the dot
-- that draws it on the shape wears the same colour - read from the client's own table, so the
-- page and the bag cannot drift apart. White and green vanish on tan paper, which is what the
-- black outline on every face is for.
local RUNE_INK, RUNE_RGB = {}, {}
for quality = 1, 5 do
    local color = ITEM_QUALITY_COLORS[quality]
    RUNE_RGB[quality] = { color.r, color.g, color.b }
    RUNE_INK[quality] = format("|cff%02x%02x%02x", math.floor(color.r * 255 + 0.5),
        math.floor(color.g * 255 + 0.5), math.floor(color.b * 255 + 0.5))
end

-- The dark surfaces keep what they had.
local DARK = { proc = ORANGE, soft = "|cff808080" }

local function Font(name, path, size, red, green, blue, flags)
    local font = _G[name] or CreateFont(name)
    font:SetFont(path, size, flags or "")
    font:SetTextColor(red, green, blue)
    return font
end

-- The heading and the word names are white, outlined in black: they carry the page the way
-- lettering carved into a cover does, and white on parchment needs the outline to hold an edge.
local titleFont = Font("RunewordsTitleFont", "Fonts\\MORPHEUS.TTF", 17, 1, 1, 1, "OUTLINE")
local nameFont = Font("RunewordsNameFont", "Fonts\\MORPHEUS.TTF", 14, 1, 1, 1, "OUTLINE")
local runeFont = Font("RunewordsRuneFont", "Fonts\\FRIZQT__.TTF", 9, 1, 1, 1, "OUTLINE")
local rollFont = Font("RunewordsRollFont", "Fonts\\FRIZQT__.TTF", 12, 1, 1, 1, "OUTLINE")
-- A size under the word names (14) and under the runes, so the entry reads name first and its
-- arithmetic second rather than as four lines of equal weight.
local effectFont = Font("RunewordsEffectFont", "Fonts\\FRIZQT__.TTF", 9, 1, 1, 1, "OUTLINE")
local metaFont = Font("RunewordsMetaFont", "Fonts\\FRIZQT__.TTF", 11, 0.15, 0.09, 0.03)

local panel = CreateFrame("Frame", "RunewordsPanel", UIParent)
panel:SetSize(PANEL_WIDTH, ROW_TOP + BOTTOM)
panel:SetPoint("CENTER", 0, -190)
panel:SetBackdrop({
    bgFile = "Interface\\AchievementFrame\\UI-Achievement-Parchment",
    edgeFile = "Interface\\Tooltips\\UI-Tooltip-Border",
    edgeSize = 12,
    insets = { left = 4, right = 4, top = 4, bottom = 4 },
})
panel:SetBackdropColor(1, 1, 1, 1)
panel:SetBackdropBorderColor(0.15, 0.10, 0.05, 1)
panel:SetMovable(true)
panel:EnableMouse(true)
panel:RegisterForDrag("LeftButton")
panel:SetScript("OnDragStart", panel.StartMoving)
panel:SetScript("OnDragStop", function(self)
    self:StopMovingOrSizing()
    local point, _, _, x, y = self:GetPoint()
    RunewordsDB.point, RunewordsDB.x, RunewordsDB.y = point, x, y
end)

local emblem = panel:CreateTexture(nil, "ARTWORK")
emblem:SetTexture("Interface\\QuestFrame\\UI-QuestLog-BookIcon")
emblem:SetSize(26, 26)
emblem:SetPoint("TOPLEFT", 8, -3)

local title = panel:CreateFontString(nil, "OVERLAY")
title:SetFontObject(titleFont)
title:SetPoint("LEFT", emblem, "RIGHT", 5, 1)
title:SetText(L.TITLE)

local countLabel = panel:CreateFontString(nil, "OVERLAY")
countLabel:SetFontObject(metaFont)
countLabel:SetPoint("TOPRIGHT", -PAD_X, -(TITLE / 2 + 3))

-- One hairline closes the heading off from the entries, the same rule the entries use. It is
-- thin on purpose: at this scale a unit is under two pixels, so it reads as the page's ruling
-- rather than as a border.
local heading = panel:CreateTexture(nil, "ARTWORK")
heading:SetTexture("Interface\\Buttons\\WHITE8X8")
heading:SetVertexColor(0.23, 0.14, 0.06, 0.45)
heading:SetHeight(1)
heading:SetPoint("TOPLEFT", PAD_X, -(TITLE + 3))
heading:SetPoint("TOPRIGHT", -PAD_X, -(TITLE + 3))

-- One row per word, made the first time the panel needs it and reused after that. Line one is
-- the word and its roll, line two the numbers the roll actually produced: a reader who wants to
-- know why they are stronger does not have to open the buff bar to find out.
local rows = {}

-- Is this grid cell part of any word that is up? The panel and the highlight must agree with
-- the module, which grants every word the bags form, not just the longest one.
local appliedSet = {}

local function InWord(bag, index)
    return appliedSet[bag .. ":" .. index] ~= nil
end

local function Row(index)
    local row = rows[index]
    if row then return row end

    row = {}
    local top = -(ROW_TOP + (index - 1) * ROW_HEIGHT)

    -- The word's shape, drawn to scale: one square per cell of the bag grid the pattern was
    -- written on, coloured by the rune that sits in it. A rune list cannot say whether two runes
    -- lie side by side or one above the other - which is the whole question - and the words are
    -- all written inside a four by four box, so the diagram always fits here.
    row.dots = {}

    row.runes = panel:CreateFontString(nil, "OVERLAY")
    row.runes:SetFontObject(runeFont)
    row.runes:SetPoint("TOPLEFT", PAD_X + 31, top - 1)
    row.runes:SetWidth(112)
    row.runes:SetJustifyH("LEFT")

    row.name = panel:CreateFontString(nil, "OVERLAY")
    row.name:SetFontObject(nameFont)
    row.name:SetPoint("TOPLEFT", TEXT_X, top + 1)
    row.name:SetWidth(210)
    row.name:SetJustifyH("LEFT")

    row.roll = panel:CreateFontString(nil, "OVERLAY")
    row.roll:SetFontObject(rollFont)
    row.roll:SetPoint("TOPRIGHT", -PAD_X, top - 1)
    row.roll:SetJustifyH("RIGHT")

    row.effects = panel:CreateFontString(nil, "OVERLAY")
    row.effects:SetFontObject(effectFont)
    row.effects:SetPoint("TOPLEFT", TEXT_X, top - 14)
    row.effects:SetPoint("RIGHT", panel, "RIGHT", -PAD_X, 0)
    row.effects:SetJustifyH("LEFT")

    -- A ruled line under each entry, the way a written page separates them.
    row.rule = panel:CreateTexture(nil, "ARTWORK")
    row.rule:SetTexture("Interface\\Buttons\\WHITE8X8")
    row.rule:SetVertexColor(0.23, 0.14, 0.06, 0.22)
    row.rule:SetHeight(1)
    row.rule:SetPoint("TOPLEFT", PAD_X, top - ROW_HEIGHT + 4)
    row.rule:SetPoint("TOPRIGHT", -PAD_X, top - ROW_HEIGHT + 4)

    rows[index] = row
    return row
end

-- One square of a word's shape, built the first time a row needs it: a two-rune word never makes
-- six of them. The square is tinted with its rune's quality colour at draw time.
local function Dot(row, index)
    local dot = row.dots[index]
    if not dot then
        dot = panel:CreateTexture(nil, "ARTWORK")
        dot:SetTexture("Interface\\Buttons\\WHITE8X8")
        dot:SetSize(SHAPE_PITCH - 1, SHAPE_PITCH - 1)
        row.dots[index] = dot
    end
    return dot
end

-- The roll decides how much of the stock spell the player gets. A proc is not scaled at all -
-- its damage is the triggered spell's own - so those read as a fixed number and a chance.
local function EffectLine(word, pct, worth, tint)
    tint = tint or DARK
    local parts = {}
    for _, effect in ipairs(word.effects or {}) do
        if effect.proc then
            -- Short on purpose: a word carries three effects and the line is one row tall. The
            -- school is in the name the proc already prints ("Electric Discharge"), so the
            -- number and the chance are all that is left to say.
            if effect.kind == "damage" then
                parts[#parts + 1] = format("%s%s %d (%d%%)|r",
                    tint.proc, effect.label, effect.amount, effect.chance)
            elseif effect.kind == "weapon" then
                parts[#parts + 1] = format("%s%s (weapon %d%%)|r", tint.proc, effect.label,
                    effect.chance)
            else
                parts[#parts + 1] = format("%s%s (%d%%)|r", tint.proc, effect.label, effect.chance)
            end
        else
            parts[#parts + 1] = format("%s%s %d|r", tint.soft, effect.label,
                math.floor(effect.amount * worth / 100 + 0.5))
        end
    end
    return table.concat(parts, tint.soft .. " · |r")
end

-- A rune rolls 1..100 and a word is the mean of its runes, so the expected value is a touch over
-- 50: the scale is centred there, and 50% is a fair word rather than a failing one. Five bands,
-- because with the average being the whole rule the interesting question is how a word stands
-- against its own 50, and green/gold/red alone cannot say "fair" and "poor" apart from "bad".
-- The panel's bands are bright - the percentage carries an outline, which is what lets a bright
-- colour sit on tan paper.
local ROLL_INK = { "|cff20e020", "|cff5aa800", "|cffa86f00", "|cffb84000", "|cffa01410" }
local ROLL_DARK = { GREEN, "|cff9adf4a", GOLD, ORANGE, RED }

local function RollColour(pct, bands)
    bands = bands or ROLL_DARK
    if pct >= 90 then return bands[1] end
    if pct >= 70 then return bands[2] end
    if pct >= 50 then return bands[3] end
    if pct >= 30 then return bands[4] end
    return bands[5]
end

local applied = {}

local function Refresh()
    if not RunewordsDB.enabled or (UnitLevel("player") or 0) < 1 then
        applied, appliedSet = {}, {}
        panel:Hide()
        return
    end

    applied = ResolveAll()
    appliedSet = {}
    for _, entry in ipairs(applied) do
        for key in pairs(entry.slots) do appliedSet[key] = true end
    end
    if #applied == 0 then
        panel:Hide()
        return
    end

    local shown = math.min(#applied, MAX_ROWS)
    for index = 1, shown do
        local entry = applied[index]
        local word = entry.word
        local row = Row(index)

        -- The cell order is the word's own path, so the runes read in the order the shape runs.
        local names = {}
        for _, cell in ipairs(word.cells) do
            local rune = runeData[cell[3]]
            names[#names + 1] = (rune and RUNE_INK[rune.quality] or INK.soft)
                .. (rune and rune.name or "?") .. "|r"
        end
        row.runes:SetText(table.concat(names, INK.soft .. "·|r"))
        row.name:SetText(word.name)

        -- The line, drawn on the bag's own axes: across reads left to right and down reads top
        -- to bottom, which is the order the runes are listed in beside it. A word of five or six
        -- runes is a column that has to fit the row's height, so its squares sit closer together.
        local pitch = #word.cells > 4 and SHAPE_PITCH - 2 or SHAPE_PITCH
        local shapeTop = -(ROW_TOP + (index - 1) * ROW_HEIGHT) + 4
        for position, cell in ipairs(word.cells) do
            local rune = runeData[cell[3]]
            local rgb = RUNE_RGB[rune and rune.quality] or { 0.5, 0.5, 0.5 }
            local dot = Dot(row, position)
            local step = (position - 1) * pitch
            dot:SetSize(pitch - 1, pitch - 1)
            dot:ClearAllPoints()
            if word.horizontal then
                dot:SetPoint("TOPLEFT", panel, "TOPLEFT", PAD_X + step, shapeTop)
            else
                dot:SetPoint("TOPLEFT", panel, "TOPLEFT", PAD_X, shapeTop - step)
            end
            dot:SetVertexColor(rgb[1], rgb[2], rgb[3])
            dot:Show()
        end
        for position = #word.cells + 1, #row.dots do
            row.dots[position]:Hide()
        end

        if entry.known then
            local worth = Worth(word, entry.pct)
            row.roll:SetText(RollColour(entry.pct, ROLL_INK) .. format("%d%%", entry.pct) .. "|r")
            row.effects:SetText(EffectLine(word, entry.pct, worth, INK))
        else
            row.roll:SetText(INK.soft .. L.ROLL_UNKNOWN .. "|r")
            row.effects:SetText(INK.soft .. L.PENDING .. "|r")
        end
        row.rule:Show()
        row.runes:Show(); row.name:Show(); row.roll:Show(); row.effects:Show()
    end

    for index = shown + 1, #rows do
        local row = rows[index]
        row.rule:Hide()
        for position = 1, #row.dots do row.dots[position]:Hide() end
        row.runes:Hide(); row.name:Hide(); row.roll:Hide(); row.effects:Hide()
    end

    -- The panel has no footer now, so the count says everything the footer used to: how many
    -- words are up, and whether all of them fit on the page.
    if #applied > MAX_ROWS then
        countLabel:SetText(format(L.HELD_MORE, #applied, shown))
    else
        countLabel:SetText(format(L.HELD, #applied))
    end

    panel:SetHeight(ROW_TOP + shown * ROW_HEIGHT + BOTTOM)
    if RunewordsDB.panel then panel:Show() end
end

-- ---------------------------------------------------------------------------
-- The bag highlight
-- ---------------------------------------------------------------------------

-- One border per item button, made the first time that button shows a rune. It is a child of
-- the button, so the client moves and hides it with the slot it belongs to.
-- A cell of a completed word gets two frames: the crisp border that says "this is one of the
-- runes", and a second, wider border outside it drawn only into the corners, which breathes so
-- the word reads across a full bag instead of blending into the slot frames. Lone runes keep the
-- plain blue border.
local glowing = {}

local glowDriver = CreateFrame("Frame")
glowDriver:SetScript("OnUpdate", function()
    -- Slow and shallow: enough movement to catch the eye, not enough to be noise.
    local alpha = 0.55 + 0.45 * math.abs(math.sin(GetTime() * 2.2))
    for border in pairs(glowing) do
        border.glow:SetAlpha(alpha)
    end
end)

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

        local glow = CreateFrame("Frame", nil, button)
        glow:SetPoint("TOPLEFT", -3, 3)
        glow:SetPoint("BOTTOMRIGHT", 3, -3)
        glow:SetFrameLevel(button:GetFrameLevel() + 3)
        glow:SetBackdrop({
            edgeFile = "Interface\\Tooltips\\UI-Tooltip-Border",
            edgeSize = 18,
            insets = { left = 2, right = 2, top = 2, bottom = 2 },
        })
        glow:SetBackdropBorderColor(1, 0.82, 0, 1)
        glow:SetAlpha(0)
        glow:Hide()
        border.glow = glow

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
                local inWord = InWord(bag, slot - 1)
                if inWord then
                    border:SetBackdropBorderColor(1, 0.82, 0, 1)   -- part of the word
                    border.glow:Show()
                    glowing[border] = true
                else
                    border:SetBackdropBorderColor(0.3, 0.6, 1, 0.5) -- a rune on its own
                    glowing[border] = nil
                    border.glow:Hide()
                end
                border:Show()
            elseif border then
                glowing[border] = nil
                border.glow:Hide()
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

    -- What a vendor pays: the item's own price is the rarity's floor, and the roll pays up to that
    -- again. The number comes from Runewords_Data.lua, so it is the same price the SQL writes.
    local sell = rune.sell or 0
    if sell > 0 then
        if roll then
            -- Integer copper, the same round-down the module's payout does.
            self:AddLine(format(L.VALUE, GetMoneyString(sell + math.floor(sell * roll / 100), true),
                GetMoneyString(sell, true)), 0.8, 0.8, 0.8, true)
        else
            self:AddLine(format(L.VALUE_UNKNOWN, GetMoneyString(sell, true)), 0.6, 0.6, 0.6, true)
        end
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
    local name = tooltip:GetName()
    local first = name and _G[name .. "TextLeft1"]
    local text = first and first:GetText()
    if not text then return end

    for _, entry in ipairs(applied) do
        for _, spell in ipairs(entry.word.spells) do
            if spell ~= 0 and GetSpellInfo(spell) == strtrim(text) then
                tooltip:AddLine(" ")
                tooltip:AddLine(GOLD .. entry.word.name .. "|r  " ..
                    (entry.known and (RollColour(entry.pct) .. format("%d%%", entry.pct) .. "|r") or
                        (GREY .. L.ROLL_UNKNOWN .. "|r")), 1, 1, 1, true)
                tooltip:AddLine(EffectLine(entry.word, entry.pct,
                    entry.known and Worth(entry.word, entry.pct) or 0), 1, 1, 1, true)
                return
            end
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
-- The level is part of the match: a word the character outgrew is not one it can form, so the
-- panel has to re-resolve when the level moves, not when the next bag does.
frame:RegisterEvent("PLAYER_LEVEL_UP")

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
