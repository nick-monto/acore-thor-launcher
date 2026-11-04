-- Skateboard: the client half of the realm's mod-skateboard.
--
-- The board itself is server-side: the module owns the momentum, the speed cap and the tricks.
-- This add-on only owns the input and the readout. It declares click bindings on hidden buttons
-- (no SecureActionButtonTemplate - nothing here is protected), sends one add-on whisper per
-- command to the server module and parses the reply it gets back.
--
-- The wire format is the core's own add-on command channel, and the tab is not ours to send:
-- SendAddonMessage(prefix, text, ...) puts exactly one tab between prefix and text, and
-- ChatHandler::ParseCommands expects "AzerothCore\t" + one opcode byte + the 4-digit counter +
-- the command (Chat.cpp:993-1017). So the prefix is "AzerothCore" and never "AzerothCore\t"; the
-- same rule the in-tree mod-e-interact follows with "EINT".
--
-- Replies come back as CHAT_MSG_ADDON, not CHAT_MSG_WHISPER: the module answers with
-- BuildChatPacket(CHAT_MSG_WHISPER, LANG_ADDON, ...) (Chat.cpp:1033), and a LANG_ADDON message is
-- delivered to the client as an add-on message split at the tab - arg1 is the prefix, arg2 is the
-- body, arg4 is the sender - which is how EInteract.lua reads its own replies.
--
-- Boarding takes Skateboard.BoardCastMs on the server, and its bar is not a spell cast: the module
-- pushes one add-on line of its own (opcode 'c', its own opcode, not one of the core's a/o/f/m):
-- "cbegin=<ms>", "cdone" or "ccancel=<reason>". That is one unsolicited whisper per cast instead of
-- the client guessing from a five-second status poll, and it is why a stopped cast says why.
--
-- Boarding is a click binding, so the stock Key Bindings frame lists every action and ConsolePort
-- can put any of them on a pad button through its own /cp menu. This add-on never claims a pad
-- button and never routes anything through the action bar, so it cannot taint ConsolePort.

local PREFIX = "AzerothCore"
local RUN_RATE = 7.0 -- the 7.0 yd/s run rate the module caps against

local defaults = { takeover = true, hud = true, showBar = true, bound = false }

local function Print(msg)
    DEFAULT_CHAT_FRAME:AddMessage("|cff33ff99Skateboard:|r " .. tostring(msg))
end

local function OnOff(value)
    return value and "on" or "off"
end

-- The last status reply. The module's status line is the only place these come from.
local boarded, tricks, pushOk, deck, cap = false, 0, 1, 0, 0
local inWorld = false
local nextPoll = 0

-- Pending commands, keyed by their 4-digit counter. A message line may arrive before the final
-- o/f line, so the lines are accumulated per command and read when that command finishes.
local counter = 0
local pending = {}

local Send
local ApplyStatus

-- A refusal that repeats is said once. The status line is polled every few seconds, so a realm
-- with no 'board' command (an artifact built before mod-skateboard) or one with
-- Skateboard.Enable = 0 would otherwise answer every poll with the same sentence, and the player
-- gets a line of chat every five seconds for nothing. The same goes for a push on a board the
-- server has already dropped: one line, not one per press.
local lastRefusal, refused, quiet = nil, 0, false

local function Refuse(text)
    refused = refused + 1
    if text ~= lastRefusal then
        Print(text)
        lastRefusal = text
    end
    -- Three in a row is a server that will keep saying it: stop polling until the player asks
    -- again (a click or /sb), and let a zone change or a reload start clean.
    if refused >= 3 then quiet = true end
end

local function Reset()
    lastRefusal, refused, quiet = nil, 0, false
end

local function Finish(entry, ok)
    local text = table.concat(entry.lines, "\n")
    if ok then
        -- The server answered: whatever was refusing has stopped, so the poll is worth running.
        lastRefusal, refused, quiet = nil, 0, false
    end
    if entry.command == "board status" then
        local line
        for _, l in ipairs(entry.lines) do
            if string.find(l, "boarded=", 1, true) then line = l end
        end
        if line then ApplyStatus(line) end
        if not ok and text ~= "" then Refuse(text) end
        if entry.handler then entry.handler(ok, entry.lines) end
    else
        if not ok then
            Refuse(text ~= "" and text or "the server refused the command.")
        elseif entry.handler then
            entry.handler(true, entry.lines)
        end
    end
end

-- poll: true for the background status read, which is the only caller that answers the same
-- sentence over and over. Anything the player asked for reopens the poll.
Send = function(command, handler, poll)
    if not poll then Reset() end
    counter = counter % 9999 + 1
    local id = string.format("%04d", counter)
    pending[id] = { command = command, lines = {}, handler = handler }
    SendAddonMessage(PREFIX, "i" .. id .. command, "WHISPER", UnitName("player"))
end

-- Every binding: the hidden button, its label for the stock Key Bindings frame and ConsolePort,
-- and the key it gets on the first run if that key is still free.
local BINDINGS = {
    { button = "SkateboardButtonBoard", label = "Skateboard", key = "B" },
    { button = "SkateboardButtonPush", label = "Skateboard: push", key = "G" },
    { button = "SkateboardButtonTrick1", label = "Skateboard: trick 1", key = "T" },
    { button = "SkateboardButtonTrick2", label = "Skateboard: trick 2", key = "Y" },
    { button = "SkateboardButtonBail", label = "Skateboard: bail", key = "X" },
}

-- Which action button becomes which board action while the player is on the board.
local TAKEOVER = { "SkateboardButtonTrick1", "SkateboardButtonTrick2", "SkateboardButtonPush" }

local function ClickBinding(button)
    return "CLICK " .. button .. ":LeftButton"
end

local pendingTakeover, pendingRestore

-- The takeover, in the user's own words: while boarded and out of combat, the keys the player
-- already has on action buttons 1/2/3 are re-pointed at the board's buttons, so the player's own
-- 1/2/3 become board actions without touching ConsolePort or the action bar itself. The original
-- keys are remembered in SavedVariables, so a reload or a crash restores them on the next login
-- instead of leaving the player's bar hijacked.
local function TakeOver()
    if not SkateboardDB or not SkateboardDB.takeover then return end
    if InCombatLockdown() then
        pendingTakeover = true
        return
    end
    pendingTakeover = nil
    if SkateboardDB.saved then return end
    local saved = {}
    for i = 1, 3 do
        local keys = {}
        local first, second = GetBindingKey("ACTIONBUTTON" .. i)
        if first then tinsert(keys, first) end
        if second then tinsert(keys, second) end
        saved[i] = keys
        for _, key in ipairs(keys) do
            SetBinding(key, ClickBinding(TAKEOVER[i]))
        end
    end
    SkateboardDB.saved = saved
end

local function Restore()
    local saved = SkateboardDB and SkateboardDB.saved
    if not saved then
        pendingRestore = nil
        return
    end
    if InCombatLockdown() then
        pendingRestore = true
        return
    end
    pendingRestore = nil
    for i = 1, 3 do
        local keys = saved[i]
        if keys then
            for _, key in ipairs(keys) do
                SetBinding(key, "ACTIONBUTTON" .. i)
            end
        end
    end
    SkateboardDB.saved = nil
end

local bar = CreateFrame("Frame", "SkateboardBar", UIParent)
bar:SetWidth(420)
bar:SetHeight(32)
bar:SetPoint("CENTER", UIParent, "CENTER", 0, -180)
bar:SetMovable(true)
bar:EnableMouse(true)
bar:RegisterForDrag("LeftButton")
bar:SetClampedToScreen(true)
bar:SetScript("OnDragStart", function(self) self:StartMoving() end)
bar:SetScript("OnDragStop", function(self) self:StopMovingOrSizing() end)
bar:SetBackdrop({
    bgFile = "Interface\\Tooltips\\UI-Tooltip-Background",
    edgeFile = "Interface\\Tooltips\\UI-Tooltip-Border",
    tile = true, tileSize = 16, edgeSize = 16,
    insets = { left = 4, right = 4, top = 4, bottom = 4 },
})
bar:SetBackdropColor(0, 0, 0, 0.6)
bar:Hide()

-- The bar is the whole board in five buttons: board or leave, push, three tricks. It is on screen
-- whenever the player has not turned it off, so leaving a board never hides the way back on.
local boardButton = CreateFrame("Button", "SkateboardBarBoard", bar, "UIPanelButtonTemplate")
boardButton:SetSize(64, 22)
boardButton:SetPoint("LEFT", bar, "LEFT", 6, 0)
boardButton:SetText("Board")
boardButton:SetScript("OnClick", function()
    Send(boarded and "board off" or "board on", function(ok) if ok then Send("board status") end end)
end)

local pushButton = CreateFrame("Button", "SkateboardBarPush", bar, "UIPanelButtonTemplate")
pushButton:SetSize(64, 22)
pushButton:SetPoint("LEFT", bar, "LEFT", 6 + 68, 0)
pushButton:SetText("Push")
pushButton:SetScript("OnClick", function() Send("board push") end)

local trickButtons = {}
for i = 1, 3 do
    local b = CreateFrame("Button", "SkateboardBarTrick" .. i, bar, "UIPanelButtonTemplate")
    b:SetSize(64, 22)
    b:SetPoint("LEFT", bar, "LEFT", 6 + (i + 1) * 68, 0)
    b:SetText("Trick " .. i)
    b:SetScript("OnClick", function() Send("board trick " .. i) end)
    trickButtons[i] = b
end

local hud = CreateFrame("Frame", "SkateboardHUD", UIParent)
hud:SetWidth(240)
hud:SetHeight(20)
hud:SetPoint("CENTER", UIParent, "CENTER", 0, 120)
local hudText = hud:CreateFontString(nil, "OVERLAY", "GameFontNormal")
hudText:SetAllPoints(hud)
hud:Hide()

-- The boarding cast, drawn by this add-on: the server owns the timer and pushes start/stop, so the
-- bar cannot outlive the cast. Only one visible at a time - boarding twice is refused server-side.
local castEnd, castMs = nil, 0
local castBar = CreateFrame("StatusBar", "SkateboardCastBar", UIParent)
castBar:SetSize(180, 16)
castBar:SetPoint("CENTER", UIParent, "CENTER", 0, 70)
castBar:SetStatusBarTexture("Interface\\TargetingFrame\\UI-StatusBar")
castBar:SetMinMaxValues(0, 1)
castBar:SetValue(0)
castBar:Hide()
local castLabel = castBar:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
castLabel:SetAllPoints(castBar)
castLabel:SetText("Boarding...")

local function StopCast()
    castEnd, castMs = nil, 0
    castBar:Hide()
end

local function ShowCast(ms)
    castMs = ms
    castEnd = GetTime() + ms / 1000
    castBar:SetMinMaxValues(0, ms / 1000)
    castBar:SetValue(ms / 1000)
    castBar:Show()
end

local function UpdateCast()
    if not castEnd then return end
    local left = castEnd - GetTime()
    if left <= 0 then
        StopCast()
        return
    end
    castBar:SetValue(left)
end

local CAST_REASON = {
    moved = "you moved",
    hit = "you were hit",
    combat = "you entered combat",
    interrupted = "interrupted",
    cancelled = "cancelled",
    disabled = "the realm turned the skateboard off",
}

local function CastEvent(payload)
    local verb, arg = string.match(payload, "^(%a+)=?(.*)$")
    if verb == "begin" then
        ShowCast(tonumber(arg) or 0)
    elseif verb == "done" then
        StopCast()
    elseif verb == "cancel" then
        StopCast()
        Refuse("Boarding stopped: " .. (CAST_REASON[arg] or arg) .. ".")
    end
end

local function RefreshUI()
    boardButton:SetText(boarded and "Bail" or "Board")
    if boarded and pushOk == 1 then pushButton:Enable() else pushButton:Disable() end
    for i, b in ipairs(trickButtons) do
        if boarded and i <= tricks then b:Show() else b:Hide() end
    end
    local showBar = SkateboardDB and SkateboardDB.showBar
    local showHud = boarded and SkateboardDB and SkateboardDB.hud
    if showBar then bar:Show() else bar:Hide() end
    if showHud then hud:Show() else hud:Hide() end
end

-- Two numbers, because neither one alone answers "how fast am I": GetUnitSpeed is what the client
-- is doing this frame and reads 0 whenever the player is standing still with the deck under them,
-- while the cap is what the module is holding and is the number its debug line prints. The cap
-- arrives with the status poll, so it lags a poll behind the movement.
local function UpdateHUD()
    if not (boarded and SkateboardDB and SkateboardDB.hud) then return end
    local speed = GetUnitSpeed("player") or 0
    hudText:SetText(string.format("Speed %.1f yd/s (%.0f%% of run), cap %.1f", speed, speed / RUN_RATE * 100, cap))
end

ApplyStatus = function(line)
    local _, _, b, t, p, d, sp = string.find(line, "boarded=(%d) tricks=(%d) push=(%d) deck=(%d+) speed=([%d%.]+)")
    if not b then return end
    boarded = (b == "1")
    tricks = tonumber(t) or 0
    pushOk = tonumber(p) or 0
    deck = tonumber(d) or 0
    cap = tonumber(sp) or 0
    if boarded then
        TakeOver()
    else
        Restore()
    end
    RefreshUI()
end

-- The five hidden buttons. Plain Button frames: nothing here is protected, so a click binding is
-- enough. The BINDING_NAME global is what the stock Key Bindings frame prints; without it the
-- frame would only show the raw "CLICK SkateboardButtonBoard:LeftButton" string.
local function ApplyFirstRun()
    if SkateboardDB.bound then return end
    SkateboardDB.bound = true
    for _, b in ipairs(BINDINGS) do
        local binding = ClickBinding(b.button)
        if not GetBindingKey(binding) and not GetBindingKey(b.key) then
            SetBindingClick(b.key, b.button)
        end
    end
end

-- ConsolePort builds its /cp action list on demand from GetCustomBindings(). A click binding from
-- another add-on is otherwise shown raw there, so one readable row per binding is appended to
-- whatever the function returns. Wrapped, never replaced, and only once.
local hooked
local function HookConsolePort()
    if hooked or type(ConsolePort) ~= "table" or type(ConsolePort.GetCustomBindings) ~= "function" then
        return
    end
    hooked = true
    local get = ConsolePort.GetCustomBindings
    ConsolePort.GetCustomBindings = function(self, ...)
        local list = get(self, ...)
        if type(list) == "table" then
            for _, b in ipairs(BINDINGS) do
                tinsert(list, { name = b.label, binding = ClickBinding(b.button) })
            end
        end
        return list
    end
end

local function CreateBindingButtons()
    local actions = {
        SkateboardButtonBoard = function() Send("board toggle") end,
        SkateboardButtonPush = function() Send("board push") end,
        SkateboardButtonTrick1 = function() Send("board trick 1") end,
        SkateboardButtonTrick2 = function() Send("board trick 2") end,
        SkateboardButtonBail = function()
            Send("board off", function(ok) if ok then Send("board status") end end)
        end,
    }
    for _, b in ipairs(BINDINGS) do
        local button = CreateFrame("Button", b.button, UIParent)
        button:SetScript("OnClick", actions[b.button])
        _G["BINDING_NAME_" .. ClickBinding(b.button)] = b.label
    end
end

CreateBindingButtons()

local frame = CreateFrame("Frame")
local elapsed = 0

frame:RegisterEvent("PLAYER_LOGIN")
frame:RegisterEvent("PLAYER_ENTERING_WORLD")
frame:RegisterEvent("PLAYER_LOGOUT")
frame:RegisterEvent("PLAYER_REGEN_ENABLED")
frame:RegisterEvent("PLAYER_DEAD")
frame:RegisterEvent("CHAT_MSG_ADDON")
frame:SetScript("OnEvent", function(self, event, arg1, arg2, arg3, arg4)
    if event == "CHAT_MSG_ADDON" then
        -- arg1 is the prefix without the tab, arg2 is everything after it, arg4 is the sender.
        if arg1 ~= PREFIX or arg4 ~= UnitName("player") then return end
        local body = arg2 or ""
        local op = string.sub(body, 1, 1)
        if op == "c" then
            CastEvent(string.sub(body, 2))
            return
        end
        local id = string.sub(body, 2, 5)
        local payload = string.sub(body, 6)
        local entry = pending[id]
        if not entry then return end
        if op == "m" then
            tinsert(entry.lines, payload)
        elseif op == "o" or op == "f" then
            pending[id] = nil
            Finish(entry, op == "o")
        end
    elseif event == "PLAYER_LOGIN" then
        SkateboardDB = SkateboardDB or {}
        for k, v in pairs(defaults) do
            if SkateboardDB[k] == nil then SkateboardDB[k] = v end
        end
        ApplyFirstRun()
        HookConsolePort()
        -- A saved table is a takeover that did not get undone - a reload, a logout or a crash.
        Restore()
        RefreshUI()
        Send("board status", nil, true)
    elseif event == "PLAYER_ENTERING_WORLD" then
        inWorld = true
        nextPoll = GetTime() + 5
        Send("board status", nil, true)
    elseif event == "PLAYER_LOGOUT" then
        inWorld = false
        StopCast()
        Restore()
    elseif event == "PLAYER_REGEN_ENABLED" then
        if pendingRestore then
            Restore()
        elseif pendingTakeover and boarded then
            TakeOver()
        end
    elseif event == "PLAYER_DEAD" then
        StopCast()
        bar:Hide()
        hud:Hide()
    end
end)

-- A light poll: board state is server-side and a crash, a death or a cleanup can take it away
-- without the client noticing, so the status line is re-read every few seconds.
frame:SetScript("OnUpdate", function(self, dt)
    elapsed = elapsed + dt
    if elapsed < 0.1 then return end
    elapsed = 0
    UpdateHUD()
    UpdateCast()
    local now = GetTime()
    if inWorld and not quiet and now >= nextPoll then
        nextPoll = now + 5
        Send("board status", nil, true)
    end
end)

SLASH_SKATEBOARD1 = "/sb"
SLASH_SKATEBOARD2 = "/skateboard"
SlashCmdList.SKATEBOARD = function(msg)
    local cmd, arg = strsplit(" ", strtrim(msg or ""), 2)
    cmd = (cmd or ""):lower()
    arg = (arg or ""):lower()
    if cmd == "on" or cmd == "off" or cmd == "toggle" then
        Send("board " .. cmd, function(ok)
            if ok then Send("board status") end
        end)
    elseif cmd == "status" then
        Send("board status", function()
            Print(string.format("boarded %s, tricks %d, push %s, deck %d",
                OnOff(boarded), tricks, OnOff(pushOk == 1), deck))
        end)
    elseif cmd == "keys" then
        for _, b in ipairs(BINDINGS) do
            local first, second = GetBindingKey(ClickBinding(b.button))
            Print(string.format("%s: %s%s", b.label, first or "none", second and (", " .. second) or ""))
        end
    elseif cmd == "takeover" and (arg == "on" or arg == "off") then
        SkateboardDB.takeover = (arg == "on")
        if SkateboardDB.takeover then
            if boarded then TakeOver() end
        else
            Restore()
        end
        Print("takeover " .. OnOff(SkateboardDB.takeover))
    elseif cmd == "hud" and (arg == "on" or arg == "off") then
        SkateboardDB.hud = (arg == "on")
        RefreshUI()
        Print("hud " .. OnOff(SkateboardDB.hud))
    elseif cmd == "bar" then
        SkateboardDB.showBar = not SkateboardDB.showBar
        RefreshUI()
        Print("bar " .. OnOff(SkateboardDB.showBar))
    else
        Print("usage: /sb on | off | toggle | status | keys | takeover on|off | hud on|off | bar")
        Print(string.format("boarded %s, tricks %d, push %s, deck %d",
            OnOff(boarded), tricks, OnOff(pushOk == 1), deck))
    end
end
