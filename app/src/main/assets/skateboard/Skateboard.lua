-- Skateboard: the client half of the realm's mod-skateboard.
--
-- The board itself is server-side: the module owns the momentum, the speed cap and the tricks.
-- This add-on only owns the input and the readout. It declares click bindings on hidden buttons
-- (no SecureActionButtonTemplate - nothing here is protected), sends one add-on whisper per
-- command to the server module and parses the whisper it gets back from itself. The server
-- rewrites the command channel reply as "AzerothCore\t<op><counter><payload>" with o = ok,
-- f = failed and m = message.
--
-- Boarding is a click binding, so the stock Key Bindings frame lists every action and ConsolePort
-- can put any of them on a pad button through its own /cp menu. This add-on never claims a pad
-- button and never routes anything through the action bar, so it cannot taint ConsolePort.

local PREFIX = "AzerothCore\t"
local RUN_RATE = 7.0 -- the 7.0 yd/s run rate the module caps against

local defaults = { takeover = true, hud = true, showBar = true, bound = false }

local function Print(msg)
    DEFAULT_CHAT_FRAME:AddMessage("|cff33ff99Skateboard:|r " .. tostring(msg))
end

local function OnOff(value)
    return value and "on" or "off"
end

-- The last status reply. The module's status line is the only place these come from.
local boarded, tricks, pushOk, deck = false, 0, 1, 0
local inWorld = false
local nextPoll = 0

-- Pending commands, keyed by their 4-digit counter. A message line may arrive before the final
-- o/f line, so the lines are accumulated per command and read when that command finishes.
local counter = 0
local pending = {}

local Send
local ApplyStatus

local function Finish(entry, ok)
    local text = table.concat(entry.lines, "\n")
    if entry.command == "board status" then
        local line
        for _, l in ipairs(entry.lines) do
            if string.find(l, "boarded=", 1, true) then line = l end
        end
        if line then ApplyStatus(line) end
        if not ok and text ~= "" then Print(text) end
        if entry.handler then entry.handler(ok, entry.lines) end
    else
        if not ok then
            Print(text ~= "" and text or "the server refused the command.")
        elseif entry.handler then
            entry.handler(true, entry.lines)
        end
    end
end

Send = function(command, handler)
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

local pushButton = CreateFrame("Button", "SkateboardBarPush", bar, "UIPanelButtonTemplate")
pushButton:SetSize(64, 22)
pushButton:SetPoint("LEFT", bar, "LEFT", 6, 0)
pushButton:SetText("Push")
pushButton:SetScript("OnClick", function() Send("board push") end)

local trickButtons = {}
for i = 1, 3 do
    local b = CreateFrame("Button", "SkateboardBarTrick" .. i, bar, "UIPanelButtonTemplate")
    b:SetSize(64, 22)
    b:SetPoint("LEFT", bar, "LEFT", 6 + i * 68, 0)
    b:SetText("Trick " .. i)
    b:SetScript("OnClick", function() Send("board trick " .. i) end)
    trickButtons[i] = b
end

local bailButton = CreateFrame("Button", "SkateboardBarBail", bar, "UIPanelButtonTemplate")
bailButton:SetSize(64, 22)
bailButton:SetPoint("LEFT", bar, "LEFT", 6 + 4 * 68, 0)
bailButton:SetText("Bail")
bailButton:SetScript("OnClick", function()
    Send("board off", function(ok) if ok then Send("board status") end end)
end)

local hud = CreateFrame("Frame", "SkateboardHUD", UIParent)
hud:SetWidth(240)
hud:SetHeight(20)
hud:SetPoint("CENTER", UIParent, "CENTER", 0, 120)
local hudText = hud:CreateFontString(nil, "OVERLAY", "GameFontNormal")
hudText:SetAllPoints(hud)
hud:Hide()

local function RefreshUI()
    for i, b in ipairs(trickButtons) do
        if i <= tricks then b:Show() else b:Hide() end
    end
    if pushOk == 1 then pushButton:Enable() else pushButton:Disable() end
    local showBar = boarded and SkateboardDB and SkateboardDB.showBar
    local showHud = boarded and SkateboardDB and SkateboardDB.hud
    if showBar then bar:Show() else bar:Hide() end
    if showHud then hud:Show() else hud:Hide() end
end

-- The readout is the cap the module publishes through GetUnitSpeed, not the client's actual
-- displacement: it stays non-zero while coasting with no key held, which is the momentum the
-- module is carrying, and it drops when friction and terrain bring the deck to a stop.
local function UpdateHUD()
    if not (boarded and SkateboardDB and SkateboardDB.hud) then return end
    local speed = GetUnitSpeed("player") or 0
    hudText:SetText(string.format("Speed %.1f yd/s (%.0f%% of run)", speed, speed / RUN_RATE * 100))
end

ApplyStatus = function(line)
    local _, _, b, t, p, d = string.find(line, "boarded=(%d) tricks=(%d) push=(%d) deck=(%d+)")
    if not b then return end
    boarded = (b == "1")
    tricks = tonumber(t) or 0
    pushOk = tonumber(p) or 0
    deck = tonumber(d) or 0
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
frame:RegisterEvent("CHAT_MSG_WHISPER")
frame:SetScript("OnEvent", function(self, event, arg1, arg2)
    if event == "CHAT_MSG_WHISPER" then
        if arg2 ~= UnitName("player") then return end
        local text = arg1 or ""
        if string.sub(text, 1, #PREFIX) ~= PREFIX then return end
        local body = string.sub(text, #PREFIX + 1)
        local op = string.sub(body, 1, 1)
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
        Send("board status")
    elseif event == "PLAYER_ENTERING_WORLD" then
        inWorld = true
        nextPoll = GetTime() + 5
        Send("board status")
    elseif event == "PLAYER_LOGOUT" then
        inWorld = false
        Restore()
    elseif event == "PLAYER_REGEN_ENABLED" then
        if pendingRestore then
            Restore()
        elseif pendingTakeover and boarded then
            TakeOver()
        end
    elseif event == "PLAYER_DEAD" then
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
    local now = GetTime()
    if inWorld and now >= nextPoll then
        nextPoll = now + 5
        Send("board status")
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
