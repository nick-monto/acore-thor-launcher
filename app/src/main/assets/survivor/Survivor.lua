-- Survivor: the client half of the realm's mod-survivor. Dying in survivor mode strips the character
-- and leaves a container at the death position; the server whispers that position over the addon
-- channel, and this add-on keeps it in SavedVariables, draws it on the world map and points the
-- minimap at it until the container is emptied or /surv clear drops it.
--
-- The client cannot be told to draw a pin - no opcode does that, and no 3.3.5 API exposes another
-- unit's world coordinates - so the marker is this add-on's job. Nothing here touches a protected
-- frame: the pin is a texture parented to WorldMapButton, the arrow is a frame parented to Minimap,
-- and both are created on first use. The one call that changes client state, SetMapZoom, is refused
-- while the player has the world map open.

local PREFIX = "SURV"
local HELLO_INTERVAL = 60
local TICK = 0.25

-- A continent's world map is the client's 64 terrain tiles of 533.3333 yards: 1024 map units of
-- 33.3333 yards, centred on the world origin. The server reports plain world coordinates, so that is
-- the whole conversion - map unit = yards / 33.3333, fraction = (unit + 512) / 1024.
local YARDS_PER_UNIT = 33.3333
local MAP_UNITS = 1024
local WORLD_SPAN = MAP_UNITS * YARDS_PER_UNIT

-- The client's continent dropdown, in the order GetMapContinents() lists it, keyed by the Map.dbc id
-- the server reports. A map that is not one of these has no continent world map to draw on.
local CONTINENT_MAP = { 0, 1, 530, 571 }
local CONTINENT_INDEX = {}
for index, map in ipairs(CONTINENT_MAP) do CONTINENT_INDEX[map] = index end

local function Print(text)
    DEFAULT_CHAT_FRAME:AddMessage("|cff9482c9Survivor|r " .. text)
end

-- SavedVariables exist as soon as the add-on loads; creating the table here keeps /surv working even
-- if the player types it before PLAYER_LOGIN.
local function DB()
    SurvivorDB = SurvivorDB or {}
    return SurvivorDB
end

-- World coordinate to 0..1 of the continent's world map. The screen's y axis is flipped, so the
-- caller passes -y for the vertical fraction.
local function Fraction(v)
    return (v / YARDS_PER_UNIT + MAP_UNITS / 2) / MAP_UNITS
end

-- Whether the world map frame is currently showing the continent of `mapId`. Both sides go through
-- CONTINENT_INDEX, so this never depends on whether GetCurrentMapContinent() counts from 0 or 1 -
-- the only thing it assumes is that the frame and SetMapZoom() use the same convention, which they
-- do by construction. Deliberately no GetMapInfo() probe: 3.3.5 returns no map id there.
local function ShowingSiteContinent(site)
    local index = CONTINENT_INDEX[site.map]
    return index ~= nil and (GetCurrentMapContinent() or -1) + 1 == index
end

-- Reading another continent's map means zooming the world map frame, which is the player's, so a
-- probe is kept for a few seconds: someone away from their death site needs no fresher answer than
-- that, and it keeps a hidden frame from being zoomed twice a second.
local probeAt, probeMap, probeX, probeY, probeHit = 0, nil, nil, nil, false

-- The player's position on `mapId`'s continent world map, as two 0..1 numbers with y down, or nil
-- when the player is not on that continent. UI thread only: it may move the world map frame, which
-- is why it refuses while the player has that frame open.
local function PlayerFraction(mapId)
    local index = CONTINENT_INDEX[mapId]
    if not index or index > select("#", GetMapContinents()) then return nil end
    local shown = (GetCurrentMapContinent() or -1) + 1
    -- The map is already showing a map of that continent: a plain read, no frame state involved.
    if shown == index then
        local x, y = GetPlayerMapPosition("player")
        if x == 0 and y == 0 then return nil end
        return x, y
    end
    local now = GetTime()
    if probeMap == mapId and now - probeAt < 5 then
        if probeHit then return probeX, probeY end
        return nil
    end
    probeAt, probeMap, probeHit, probeX, probeY = now, mapId, false, nil, nil
    if WorldMapFrame and WorldMapFrame:IsShown() then return nil end
    SetMapZoom(index)
    local x, y = GetPlayerMapPosition("player")
    SetMapToCurrentZone()
    -- The client answers 0,0 for a map the unit is not on. That is the only same-map test a 3.3.5
    -- add-on has, and it is why the arrow disappears on another continent.
    if x == 0 and y == 0 then return nil end
    probeHit, probeX, probeY = true, x, y
    return x, y
end

local pin, arrow

-- The world map pin, a texture on Blizzard's own map button. Anonymous and created once: no named
-- global to collide with, and no SetPoint on anything but the texture itself.
local function EnsurePin()
    if pin or not WorldMapButton then return pin end
    pin = WorldMapButton:CreateTexture(nil, "OVERLAY")
    pin:SetTexture("Interface\\Icons\\Ability_Hunter_MarkedForDeath")
    pin:SetSize(20, 20)
    pin:Hide()
    return pin
end

-- The minimap arrow: a frame on the minimap rim, so it cannot cover the player arrow or the zone
-- text, with its texture rotated to the bearing and the distance in yards underneath.
local function EnsureArrow()
    if arrow or not Minimap then return arrow end
    arrow = CreateFrame("Frame", nil, Minimap)
    arrow:SetSize(32, 32)
    arrow.texture = arrow:CreateTexture(nil, "OVERLAY")
    arrow.texture:SetAllPoints(arrow)
    arrow.texture:SetTexture("Interface\\Minimap\\MinimapArrow")
    arrow.label = arrow:CreateFontString(nil, "OVERLAY", "GameFontNormalSmall")
    arrow.label:SetPoint("TOP", arrow, "BOTTOM", 0, 2)
    arrow:Hide()
    return arrow
end

-- Redraws both markers from the stored site. UI thread only; cheap enough to run on a timer, and it
-- has to be one because the world map can be zoomed or closed without firing an event we can use.
local function Refresh()
    local site = SurvivorDB and SurvivorDB.site

    local marker = EnsurePin()
    if marker then
        local width, height = WorldMapButton:GetWidth(), WorldMapButton:GetHeight()
        if site and width and height and width > 0 and height > 0 and ShowingSiteContinent(site) then
            marker:ClearAllPoints()
            marker:SetPoint(
                "CENTER", WorldMapButton, "TOPLEFT",
                Fraction(site.x) * width, -Fraction(-site.y) * height
            )
            marker:Show()
        else
            marker:Hide()
        end
    end

    local pointer = EnsureArrow()
    if not pointer then return end
    local px, py
    if site then px, py = PlayerFraction(site.map) end
    if not px then
        pointer:Hide()
        return
    end
    -- Both sides are fractions of the same continent map, so the difference is yards once scaled.
    local dx = (Fraction(site.x) - px) * WORLD_SPAN
    local dy = (py - Fraction(-site.y)) * WORLD_SPAN
    local bearing = math.atan2(dx, dy) -- radians clockwise from north
    local radius = math.max(20, (Minimap:GetWidth() or 140) / 2 - 18)
    pointer:ClearAllPoints()
    pointer:SetPoint(
        "CENTER", Minimap, "CENTER",
        math.sin(bearing) * radius, math.cos(bearing) * radius
    )
    pointer.texture:SetRotation(-bearing) -- SetRotation turns counter-clockwise; the bearing does not
    pointer.label:SetText(string.format("%d yd", math.floor(math.sqrt(dx * dx + dy * dy))))
    pointer:Show()
end

local frame = CreateFrame("Frame")
local ready, helloAt, lastHello, nextTick = false, nil, 0, 0

-- Ask the server for the site. Throttled so the client's login and entering-world pair, which fire
-- back to back, sends one question - and so a reload cannot lose a marker the player still wants.
local function RequestHello(delay)
    if not ready then return end
    local now = GetTime()
    if now - lastHello < 5 then return end
    helloAt = now + (delay or 0)
end

frame:RegisterEvent("PLAYER_LOGIN")
frame:RegisterEvent("PLAYER_ENTERING_WORLD")
frame:RegisterEvent("CHAT_MSG_ADDON")
frame:SetScript("OnEvent", function(self, event, arg1, arg2, _, arg4)
    if event == "PLAYER_LOGIN" then
        DB()
        ready = true
        RequestHello()
        Refresh()
    elseif event == "PLAYER_ENTERING_WORLD" then
        RequestHello(2) -- let the realm finish restoring the character before asking about a corpse
        Refresh()
    elseif event == "CHAT_MSG_ADDON" then
        -- The server whispers to the player's own name, the same self-whisper mod-e-interact uses.
        if arg1 ~= PREFIX or arg4 ~= UnitName("player") then return end
        local what, rest = strsplit(" ", arg2 or "", 2)
        if what == "site" then
            local map, x, y, zone, id = strsplit(" ", rest or "")
            map, x, y = tonumber(map), tonumber(x), tonumber(y)
            if not (map and x and y) then return end
            DB().site = { map = map, x = x, y = y, zone = tonumber(zone) or 0, id = tonumber(id) or 0 }
            Refresh()
        elseif what == "none" then
            DB().site = nil
            Refresh()
        end
    end
end)

frame:SetScript("OnUpdate", function()
    local now = GetTime()
    if now >= nextTick then
        nextTick = now + TICK
        Refresh()
        -- Until there is a site, ask again on a slow timer: the marker has to survive a reload that
        -- happened before the server could answer.
        if ready and not (SurvivorDB and SurvivorDB.site) and now - lastHello >= HELLO_INTERVAL then
            helloAt = now
        end
    end
    if helloAt and now >= helloAt then
        helloAt = nil
        lastHello = now
        local name = UnitName("player")
        if ready and name then SendAddonMessage(PREFIX, "hello", "WHISPER", name) end
    end
end)

SLASH_SURVIVOR1 = "/surv"
SLASH_SURVIVOR2 = "/survivor"
SlashCmdList.SURVIVOR = function(msg)
    local command = strlower(strtrim(msg or ""))
    local db = DB()
    if command == "clear" then
        db.site = nil
        Refresh()
        Print("site cleared")
    elseif command == "" or command == "status" then
        local site = db.site
        if site then
            Print(string.format(
                "death site: map %d, zone %d, %.1f %.1f (container %d)",
                site.map, site.zone or 0, site.x, site.y, site.id or 0
            ))
        else
            Print("no death site; one is recorded when you die in survivor mode")
        end
    else
        Print("usage: /surv | /surv clear")
    end
end
