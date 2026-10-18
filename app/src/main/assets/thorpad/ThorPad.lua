--------------------------------------------------------------------------
-- ThorPad: gives ConsolePort this pad's buttons without calibrating it.
--
-- A 3.3.5a client cannot read a controller, so the launcher sends the pad as keys (see the
-- launcher's pad profile) and ConsolePort reads its buttons from keybindings. Calibrating means
-- pressing every button while ConsolePort listens, which is tedious on a handheld, so this add-on
-- hands ConsolePort the same table its wizard would have built - the keys the launcher sends, plus
-- the controller type whose face buttons this pad has. ConsolePort applies that table itself at
-- login (Config/Binds.lua, LoadBindingSet).
--
-- Applied once per account; /thorpad reports it, /thorpad reset makes the next login apply again.
--------------------------------------------------------------------------

local BUTTONS = {
	-- Face buttons, in the order the mapper ConsolePort was built against sends them.
	CP_R_UP    = 'F9',  -- Y
	CP_R_RIGHT = 'F10', -- B
	CP_R_DOWN  = 'F11', -- A
	CP_R_LEFT  = 'F12', -- X
	-- D-pad.
	CP_L_UP    = 'F1',
	CP_L_RIGHT = 'F2',
	CP_L_DOWN  = 'F3',
	CP_L_LEFT  = 'F4',
	-- Centre buttons: view/back, guide, menu/start.
	CP_X_LEFT  = 'F5',
	CP_X_RIGHT = 'F6',
	-- Right shoulder and trigger.
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

	-- This pad has A/B/X/Y, so ConsolePort should draw Xbox prompts instead of guessing; and since
	-- every button it can use is bound above, it does not need to ask about the ones it cannot.
	local db = ConsolePort.GetData and ConsolePort:GetData()
	if db and db.Set then
		db:Set('type', 'XBOX')
		db:Set('skipCalibration', true)
	else
		settings.type = 'XBOX'
		settings.skipCalibration = true
	end

	for name, key in pairs(CONFLICTS) do
		if GetBindingKey(name) == key then SetBinding(name, nil) end
	end

	-- ConsolePort rebinds from its calibration table on demand; do it now, not at the next login.
	if ConsolePort.LoadBindingSet then ConsolePort:LoadBindingSet() end

	ThorPadDB.applied = true
end

local function report()
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
		report()
	end
end)

SLASH_THORPAD1 = '/thorpad'
SlashCmdList['THORPAD'] = function(msg)
	if (msg or ''):lower():find('reset') then
		ThorPadDB.applied = nil
		print('|cff739BD0ThorPad|r: reset. Relog to hand this pad\'s keys to ConsolePort again.')
	else
		report()
	end
end
