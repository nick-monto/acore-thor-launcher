--------------------------------------------------------------------------
-- ThorPad: gives ConsolePort this pad's buttons without calibrating it.
--
-- A 3.3.5a client cannot read a controller, so the launcher sends the pad as keys (see the
-- launcher's pad profile) and ConsolePort reads its buttons from keybindings. Calibrating means
-- pressing every button while ConsolePort listens, which is tedious on a handheld, so this add-on
-- hands ConsolePort the same table its wizard would have built - the keys the launcher sends, plus
-- the controller type whose face buttons this pad has (Switch, because its buttons sit where a
-- Nintendo pad's do). ConsolePort applies that table itself at login (Config/Binds.lua,
-- LoadBindingSet).
--
-- Applied once per account; /thorpad reports it, /thorpad reset makes the next login apply again.
-- It also moves the four buttons this project rebinds (L1/R1 target, L3 jump, R3 clear target) into
-- ConsolePort's saved binding set once, leaving any button the player rebound alone.
--------------------------------------------------------------------------

local BUTTONS = {
	-- Face buttons, by the position the launcher sends. This pad is laid out like a Nintendo pad, so
	-- the top button is X and the right one is A; ConsolePort's Switch theme draws those labels.
	CP_R_UP    = 'F9',  -- X
	CP_R_RIGHT = 'F10', -- A
	CP_R_DOWN  = 'F11', -- B
	CP_R_LEFT  = 'F12', -- Y
	-- D-pad.
	CP_L_UP    = 'F1',
	CP_L_RIGHT = 'F2',
	CP_L_DOWN  = 'F3',
	CP_L_LEFT  = 'F4',
	-- Centre buttons: view/back, guide, menu/start.
	CP_X_LEFT  = 'F5',
	CP_X_RIGHT = 'F6',
	-- Shoulders, which carry the targeting binds (and the triggers are the shift/ctrl modifiers).
	CP_T1 = 'F7',
	CP_T2 = 'F8',
}

-- The guide button sends the numpad's *, whose binding token is not spelled the same in every
-- client build, so this asks the client which spelling it takes rather than assuming one.
local CENTER_BINDING = 'CP_X_CENTER'
local CENTER_TOKENS = { 'NUMPADMULTIPLY', 'MULTIPLY' }

-- Wrath binds these to F1-F4, which the D-pad now holds: without clearing them, a D-pad press would
-- also pick a party member. Cleared only where they still hold the key this add-on is taking.
local CONFLICTS = {
	TARGETPARTYMEMBER1 = 'F1',
	TARGETPARTYMEMBER2 = 'F2',
	TARGETPARTYMEMBER3 = 'F3',
	TARGETPARTYMEMBER4 = 'F4',
}

-- ConsolePort copies its controller template into one saved binding set per talent spec on the first
-- login, and never reads the template again, so a changed default only ever reaches a character made
-- after the change. These are the buttons this project rebinds, with the action the template shipped
-- and the one it carries now ('was = false' means the template left the button unbound). A button the
-- player has rebound is not at the shipped action any more and is left exactly as it is.
local REBINDS = {
	{ button = 'CP_T1',   modifier = '',       was = 'ACTIONBUTTON4',      now = 'TARGETPREVIOUSENEMY' },
	{ button = 'CP_T2',   modifier = '',       was = 'ACTIONBUTTON5',      now = 'TARGETNEARESTENEMY' },
	{ button = 'CP_T1',   modifier = 'SHIFT-', was = 'TARGETNEARESTENEMY', now = 'ACTIONBUTTON4' },
	{ button = 'CP_T2',   modifier = 'SHIFT-', was = 'ACTIONBUTTON10',     now = 'ACTIONBUTTON5' },
	{ button = 'CP_T_L3', modifier = '',       was = false,                now = 'JUMP' },
	{ button = 'CP_T_R3', modifier = '',       was = false,                now = 'CP_CLEARTARGET' },
}
-- The controller template this pad plays with. Its face buttons sit where a Nintendo pad's do
-- (Y left, X top, A right, B bottom), so the Switch theme labels them the way the hardware is
-- labelled; Xbox was this add-on's first choice and is only replaced where nobody has changed it.
local CONTROLLER_TYPE = 'SWITCH'
local LEGACY_TYPES = { XBOX = true }
local PAD_VERSION = 1

local function countBound()
	local bound, missing = 0, {}
	for binding in pairs(BUTTONS) do
		if GetBindingKey(binding) then
			bound = bound + 1
		else
			table.insert(missing, binding)
		end
	end
	if GetBindingKey(CENTER_BINDING) then
		bound = bound + 1
	else
		table.insert(missing, CENTER_BINDING)
	end
	table.sort(missing)
	return bound, missing
end

local function apply()
	local settings = ConsolePortSettings
	if type(settings) ~= 'table' then
		print('|cff739BD0ThorPad|r: ConsolePort is not installed, so there is nothing to set up.')
		return
	end

	settings.calibration = settings.calibration or {}
	local calibration = settings.calibration
	for binding, key in pairs(BUTTONS) do
		-- Never overwrite a key the player picked; fill in only what nobody has bound.
		if calibration[binding] == nil then calibration[binding] = key end
	end

	if not GetBindingKey(CENTER_BINDING) then
		for _, token in ipairs(CENTER_TOKENS) do
			SetBinding(CENTER_BINDING, token)
			if GetBindingKey(CENTER_BINDING) then
				calibration[CENTER_BINDING] = token
				break
			end
		end
	end

	-- This pad has a full set of face buttons, so ConsolePort should draw that pad's prompts instead
	-- of guessing; and since every button it can use is bound above, it does not need to ask about
	-- the ones it cannot.
	local db = ConsolePort.GetData and ConsolePort:GetData()
	if db and db.Set then
		db:Set('type', CONTROLLER_TYPE)
		db:Set('skipCalibration', true)
	else
		settings.type = CONTROLLER_TYPE
		settings.skipCalibration = true
	end

	for name, key in pairs(CONFLICTS) do
		if GetBindingKey(name) == key then SetBinding(name, nil) end
	end

	-- ConsolePort reads both on demand, so apply them now rather than at the next login.
	if ConsolePort.LoadControllerTheme then ConsolePort:LoadControllerTheme() end
	if ConsolePort.LoadBindingSet then ConsolePort:LoadBindingSet() end

	ThorPadDB.applied = true
end

-- Brings a character made by an older launcher up to date: the pad's controller type, and the
-- buttons in REBINDS, each only where it still holds what the launcher shipped. Returns false when
-- ConsolePort is not readable yet, so the caller can retry at the next login instead of recording a
-- version that never took.
local function migrate()
	if not ConsolePort or not ConsolePort.GetBindingSet then return false end
	local db = ConsolePort.GetData and ConsolePort:GetData()
	local current = db and db.Get and db:Get('type') or ConsolePortSettings.type
	if LEGACY_TYPES[current] then
		if db and db.Set then
			db:Set('type', CONTROLLER_TYPE)
		else
			ConsolePortSettings.type = CONTROLLER_TYPE
		end
		-- The theme was loaded with the old type at login, so load it again with the new one.
		if ConsolePort.LoadControllerTheme then ConsolePort:LoadControllerTheme() end
	end

	if type(ConsolePortBindingSet) ~= 'table' then return false end
	local set = ConsolePort:GetBindingSet()
	if type(set) ~= 'table' then return false end

	local changed = false
	for _, entry in ipairs(REBINDS) do
		local sub = set[entry.button]
		if type(sub) ~= 'table' then
			sub = {}
			set[entry.button] = sub
		end
		local current = sub[entry.modifier]
		if (entry.was == false and current == nil) or current == entry.was then
			sub[entry.modifier] = entry.now
			changed = true
		end
	end

	-- ConsolePort binds its own table in on login; hand it the edited set now rather than next login.
	if changed and ConsolePort.LoadBindingSet then ConsolePort:LoadBindingSet(set) end
	return true
end

local function report()
	local db = ConsolePort.GetData and ConsolePort:GetData()
	local kind = db and db.Get and db:Get('type') or ConsolePortSettings.type
	print('|cff739BD0ThorPad|r: ConsolePort controller type ' .. tostring(kind) .. '.')
	local bound, missing = countBound()
	local total = 0
	for _ in pairs(BUTTONS) do total = total + 1 end
	total = total + 1
	print('|cff739BD0ThorPad|r: ' .. bound .. '/' .. total .. ' pad buttons have keys.')
	if #missing > 0 then
		print('|cff739BD0ThorPad|r: no key on ' .. table.concat(missing, ', ') ..
			' - bind those in Key Bindings, or run /thorpad reset and relog.')
	end
end

local frame = CreateFrame('Frame')
frame:RegisterEvent('ADDON_LOADED')
frame:RegisterEvent('PLAYER_ENTERING_WORLD')
frame:SetScript('OnEvent', function(self, event, arg1)
	if event == 'ADDON_LOADED' then
		if arg1 ~= 'ThorPad' then return end
		ThorPadDB = ThorPadDB or {}
		-- ConsolePort loads first (OptionalDeps), so its settings table exists by now.
		if not ThorPadDB.applied then apply() end
	else
		-- After the login work ConsolePort binds its table in, so this is the honest moment to look.
		if (ThorPadDB.pad or 0) < PAD_VERSION and migrate() then
			ThorPadDB.pad = PAD_VERSION
		end
		report()
	end
end)

SLASH_THORPAD1 = '/thorpad'
SlashCmdList['THORPAD'] = function(msg)
	if (msg or ''):lower():find('reset') then
		ThorPadDB.applied = nil
		ThorPadDB.pad = nil
		print('|cff739BD0ThorPad|r: reset. Relog to hand this pad\'s keys to ConsolePort again.')
	else
		report()
	end
end
