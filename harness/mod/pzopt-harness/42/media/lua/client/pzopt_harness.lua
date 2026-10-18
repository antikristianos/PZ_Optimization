-- pzopt harness: hands-off game runs for PZ_Optimization.
--
-- harness/run.sh writes ~/Zomboid/Lua/pzopt-harness.txt (getFileReader resolves
-- under Lua/) as key=value lines before launching the game. If the file names a
-- mode, this script continues latestSave.ini from the main menu's own tick as
-- soon as the menu accepts input (no click, no fixed wait) and, when quit_after is set, quits that many
-- seconds after the world is up. Everything else the harness does lives in
-- Java (pzopt.*), driven from the overridden WorldStreamer on the game thread.
--
-- Protocol on the flag file (it is the only state that survives: this script
-- is reloaded when the game resets Lua for the save's mod set, and the menu
-- fires OnMainMenuEnter more than once at start-up):
--   consumed=1   appended here once Continue has been triggered in this process
--   started=1    appended by pzopt.Harness once the world was up; back at the
--                menu with it set means the run is over -> quit to desktop
-- Without the file this script does nothing.

local FLAG_FILE = "pzopt-harness.txt"

local rawLines = {}

local function readFlags()
    local reader = getFileReader(FLAG_FILE, false)
    if not reader then return nil end
    local flags = {}
    local n = 0
    rawLines = {}
    while true do
        local line = reader:readLine()
        if line == nil then break end
        table.insert(rawLines, line)
        local k, v = string.match(line, "^%s*([%w_]+)%s*=%s*(.-)%s*$")
        if k then flags[k] = v; n = n + 1 end
    end
    reader:close()
    if n == 0 then return nil end
    return flags
end

local function appendFlag(line)
    -- true append: pzopt.Harness may already have added started=1 (the game auto-loads the
    -- save before the menu delay elapses), and rewriting from a stale copy would drop it
    local w = getFileWriter(FLAG_FILE, true, true)
    if not w then return end
    w:write(line .. "\n")
    w:close()
end

local pending = nil
local quitAtMs = nil
-- options_tab=<tab name> (2026-09-24, menu checks without xdotool, e.g. on the Mac): stay on the main menu, open
-- Options on that tab, log the tab names, 3 s later write Zomboid/Screenshots/pzopt-options.png, quit 2 s after.
-- options_search=<text> (Optimizations tab): typed into the tab's search box 1 s after opening, so a section far down
-- the page is on the screenshot (e.g. options_search=HDR).
-- options_select=<key> (2026-09-26): the preview panel of every options page shows that setting (its clips, text, bars)
-- instead of the page's first row, re-selected every tick until the screenshot (a mouse over the list would pick another).
-- Since 2026-10-04 the settings are one tab, "PZ Optimization" (PzoptOptionsTab); options_tab=Optimizations / Enhancements
-- / Profiler open it on the matching page. options_nav=<page> shows a page of it: home | cat:<category id>:<subcategory,
-- 0 = Overview>[:simple|advanced|everything] | problems[:<n>] | search:<text> (category ids in
-- src/lua/client/pzopt/pzopt_optimizations_layout.lua). options_shots=<page>;<page>;... shows each in turn and writes
-- Screenshots/pzopt-options-<n>.png per page (1.5 s each), then quits.
local optionsCheck = nil
local OLD_TABS = { Optimizations = "home", Enhancements = "cat:image:0", Profiler = "cat:overlay:0" }
local applyNav -- (set below; the read rig above it calls it)
-- options_joy=<step>,<step>,... (2026-10-04): the tab's controller navigation without a pad: the steps call the page's
-- own joypad handlers as the game does for a pad (focus = gain focus; down / up / left / right; a = A, never B: it leaves
-- the screen), one every 0.6 s, logging the focused element and the page after each ("[pzopt-harness] joy: ..."); shot
-- writes Screenshots/pzopt-joy-<n>.png. Quits when done.
local JOY = { id = 0, player = 0 }
-- options_read=1 (2026-10-04, "is every setting readable at 1080p"): walks every page of the PZ Optimization tab (home,
-- each category's Overview in Simple, each subcategory in Everything, every problem, a search) and every scroll stop of it.
-- Per page one geometry audit: READOVL lines for visible elements that overlap, READOUT lines for elements outside their
-- column (under the sidebar or the preview, past the right edge). Per scroll stop a screenshot pzopt-read-<n>.png and one
-- READROW line per setting whose label is fully on screen (its label rectangle in screen pixels, the text shown and the
-- setting's label: a "..." cut shows as shown ~= full). harness/options-readability.py OCRs every label from the shots and
-- asks Jev whether every setting is readable.
local function readRect(el)
    return el:getAbsoluteX(), el:getAbsoluteY(), el:getWidth(), el:getHeight()
end
local function readDesc(el)
    return tostring(el.Type) .. ":" .. string.gsub(tostring(el.pzoptLabel or el.title or el.name or ""), "%s", "_")
end
local function readPages(S)
    local pages = { "home" }
    for _, g in ipairs(S.tree.groups) do
        for _, cat in ipairs(g.cats) do
            table.insert(pages, "cat:" .. cat.id .. ":0:simple")
            for i = 1, #cat.subs do table.insert(pages, "cat:" .. cat.id .. ":" .. i .. ":everything") end
        end
    end
    for i = 1, #S.tree.problems do table.insert(pages, "problems:" .. i) end
    table.insert(pages, "search:shadow")
    return pages
end
local function readAudit(mo, spec)
    local S = mo.pzoptSearch
    local panel, G = S.panel, S.G
    local home = string.sub(spec, 1, 4) == "home"
    local left = home and 0 or (S.sidebar:getX() + S.sidebar:getWidth())
    local right = (S.preview:getIsVisible() and S.preview:getX()) or (panel:getWidth() - G.sbar)
    local els = {}
    for _, item in ipairs(S.items) do
        if not item.hidden then
            for _, e in ipairs(item.elems) do
                local el = e.el
                if el:getIsVisible() and el:getWidth() > 2 and el:getHeight() > 2 and not item.background then
                    table.insert(els, { el = el, item = item, x = el:getX(), y = el:getY(), w = el:getWidth(), h = el:getHeight() })
                end
            end
        end
    end
    local n = 0
    for i = 1, #els do
        local a = els[i]
        if a.x < left - 1 or a.x + a.w > right + 1 then
            print(string.format("[pzopt-harness] READOUT page=%s el=%s x=%d w=%d left=%d right=%d", spec, readDesc(a.el), a.x, a.w, left, right))
        end
        for j = i + 1, #els do
            local b = els[j]
            local ox = math.min(a.x + a.w, b.x + b.w) - math.max(a.x, b.x)
            local oy = math.min(a.y + a.h, b.y + b.h) - math.max(a.y, b.y)
            if ox > 1 and oy > 1 and a.item ~= b.item then
                n = n + 1
                if n <= 40 then
                    print(string.format("[pzopt-harness] READOVL page=%s a=%s b=%s overlap=%dx%d at=%d,%d", spec, readDesc(a.el), readDesc(b.el), ox, oy, math.max(a.x, b.x), math.max(a.y, b.y)))
                end
            end
        end
    end
    if not home then
        for _, b in ipairs(S.sidebar.buttons or {}) do
            if b.pzoptFits == false then
                print(string.format("[pzopt-harness] READSIDE page=%s entry=%s", spec, string.gsub(tostring(b.pzoptLabel), "%s", "_")))
            end
        end
    end
    print(string.format("[pzopt-harness] READPAGE page=%s elements=%d overlaps=%d", spec, #els, n))
end
local function readRows(mo, spec, shot)
    local S = mo.pzoptSearch
    local panel = S.panel
    local top = panel:getAbsoluteY()
    local bottom = top + panel:getHeight()
    local right = S.preview:getIsVisible() and S.preview:getAbsoluteX() or (panel:getAbsoluteX() + panel:getWidth())
    local n = 0
    for _, row in ipairs(S.keyRows) do
        if not row.hidden and row.entry then
            local label
            for _, e in ipairs(row.elems) do
                if e.el.Type == "ISLabel" and (not label or e.el:getY() < label:getY()) then label = e.el end
            end
            if label and label:getIsVisible() then
                local x, y, w, h = readRect(label)
                local more = row.labelMore or {}
                local lineH = getTextManager():getFontHeight(UIFont.Small) + 2
                local info = row.elems[#row.elems].el -- the line(s) under the label: a wrapped name's rest first
                local bottomAll = y + h + #more * lineH
                if y >= top and bottomAll <= bottom and x + w <= right then
                    n = n + 1
                    local shown = tostring(label.name)
                    for _, l in ipairs(more) do shown = shown .. " " .. l end
                    print(string.format("[pzopt-harness] READROW n=%d page=%s key=%s x=%d y=%d w=%d h=%d text=%s|shown=%s|full=%s", shot,
                        spec, row.entry.key, x, y, w, h, tostring(label.name), shown, tostring(row.entry.label)))
                    for i, l in ipairs(more) do
                        print(string.format("[pzopt-harness] READCONT n=%d page=%s key=%s x=%d y=%d w=%d h=%d text=%s", shot, spec,
                            row.entry.key, info:getAbsoluteX(), info:getAbsoluteY() + (i - 1) * lineH, getTextManager():MeasureStringX(UIFont.Small, l),
                            lineH, l))
                    end
                end
            end
        end
    end
    -- the header's texts (read back by OCR too: text drawn over them, like the search hint once, makes them fail)
    if S.panel:getYScroll() == 0 then
        local function txt(el, text)
            if el and el:getIsVisible() and text and text ~= "" then
                local x, y, w, h = readRect(el)
                print(string.format("[pzopt-harness] READTXT n=%d page=%s x=%d y=%d w=%d h=%d text=%s", shot, spec, x, y, w, h, text))
            end
        end
        txt(S.viewLabel.elems[1].el, S.viewLabel.elems[1].el.name)
        for _, v in ipairs(S.viewButtons) do txt(v.elems[1].el, v.elems[1].el.pzoptLabel) end
        txt(S.clipsLabel, S.clipsLabel.name)
        txt(S.status, S.status.name)
        -- the empty strips between the search line's elements (text spilling out of one, like the search hint at 1920 x 1080
        -- once, shows up there, not inside the next element's own rectangle)
        local line = { S.entry, S.viewLabel.elems[1].el }
        for _, v in ipairs(S.viewButtons) do table.insert(line, v.elems[1].el) end
        for _, el in ipairs({ S.status, S.clips, S.clipsLabel }) do table.insert(line, el) end
        local ey = S.entry:getAbsoluteY()
        local onLine = {}
        for _, el in ipairs(line) do
            if el:getIsVisible() and math.abs(el:getAbsoluteY() - ey) < 4 then table.insert(onLine, el) end
        end
        table.sort(onLine, function(p, q) return p:getAbsoluteX() < q:getAbsoluteX() end)
        for i = 1, #onLine - 1 do
            local ax = onLine[i]:getAbsoluteX() + onLine[i]:getWidth()
            local bx = onLine[i + 1]:getAbsoluteX()
            if bx - ax >= 6 then
                print(string.format("[pzopt-harness] READGAP n=%d page=%s x=%d y=%d w=%d h=%d between=%s|%s", shot, spec, ax + 2,
                    ey + 4, bx - ax - 4, S.entry:getHeight() - 8, readDesc(onLine[i]), readDesc(onLine[i + 1])))
            end
        end
    end
    return n
end
local function readTick(c, ms, now)
    local mo = ms.mainOptions
    local S = mo.pzoptSearch
    local r = c.read
    if not S then
        print("[pzopt-harness] READ: no pzoptSearch on the options screen")
        c.readDone, c.shotMs = true, now
        return
    end
    if not r.pages then
        r.pages, r.page, r.shot, r.at = readPages(S), 0, 0, now
        print(string.format("[pzopt-harness] READWIN x=%d y=%d w=%d h=%d screen=%dx%d pages=%d", mo:getAbsoluteX(), mo:getAbsoluteY(),
            mo:getWidth(), mo:getHeight(), getCore():getScreenWidth(), getCore():getScreenHeight(), #r.pages))
    end
    if now < r.at then return end
    if r.step == "shoot" then
        if r.scroll == 0 then readAudit(mo, r.pages[r.page]) end -- (once the page is laid out: a search runs 120 ms late)
        r.shot = r.shot + 1
        local rows = readRows(mo, r.pages[r.page], r.shot)
        getCore():TakeFullScreenshot("pzopt-read-" .. r.shot .. ".png")
        print(string.format("[pzopt-harness] READSHOT n=%d page=%s scroll=%d rows=%d", r.shot, r.pages[r.page], r.scroll, rows))
        r.step, r.at = "scroll", now + 450
        return
    end
    if r.step == "scroll" then
        local panel = S.panel
        local maxScroll = math.max(0, panel:getScrollHeight() - panel:getHeight())
        if r.scroll < maxScroll then
            r.scroll = math.min(maxScroll, r.scroll + math.floor(panel:getHeight() * 0.8))
            panel:setYScroll(-r.scroll)
            r.step, r.at = "shoot", now + 450
            return
        end
    end
    r.page = r.page + 1
    if r.page > #r.pages then
        print("[pzopt-harness] READ: done, " .. r.shot .. " screenshots")
        c.readDone, c.shotMs = true, now
        return
    end
    local spec = r.pages[r.page]
    applyNav(mo, spec)
    S.panel:setYScroll(0)
    r.scroll = 0
    r.step, r.at = "shoot", now + (string.sub(spec, 1, 7) == "search:" and 900 or 500)
end

local function joyDescribe(mo)
    local panel = mo.pzoptPanel
    local child = panel and panel:getJoypadFocus()
    local what = "none"
    if child then
        what = tostring(child.Type) .. " '" .. tostring(child.pzoptLabel or child.title or child.name or (child.options and child.options[1]) or "") .. "'"
            .. (child.pzoptFixed and " [sidebar]" or "")
    end
    local S = mo.pzoptSearch
    return what .. " line " .. tostring(panel and panel.joypadIndexY) .. "/" .. tostring(panel and #panel.joypadButtonsY)
        .. " page '" .. tostring(S and S.crumb) .. "' scroll " .. tostring(panel and math.floor(-panel:getYScroll()))
end
local function joyStep(mo, step, n)
    local panel = mo.pzoptPanel
    if step == "focus" then
        panel.joyfocus = JOY
        MainOptions.onGainJoypadFocusCurrentTab(panel, JOY)
        panel:restoreJoypadFocus(JOY)
    elseif step == "down" then panel:onJoypadDirDown(JOY)
    elseif step == "up" then panel:onJoypadDirUp(JOY)
    elseif step == "left" then panel:onJoypadDirLeft(JOY)
    elseif step == "right" then panel:onJoypadDirRight(JOY)
    elseif step == "a" then panel:onJoypadDown(Joypad.AButton, JOY)
    elseif step == "shot" then getCore():TakeFullScreenshot("pzopt-joy-" .. n .. ".png")
    end
end

applyNav = function(mo, spec)
    local parts = {}
    for part in string.gmatch(spec .. ":", "([^:]*):") do table.insert(parts, part) end
    local kind = parts[1] ~= "" and parts[1] or "home"
    if kind == "search" then
        PzoptOptionsNavigate(mo, "home")
        local S = mo.pzoptSearch
        if S and S.entry then S.entry:setText(string.sub(spec, 8)) end
        return
    end
    PzoptOptionsNavigate(mo, kind, parts[2] ~= "" and parts[2] or nil, parts[3], parts[4] ~= "" and parts[4] or nil)
end
-- compat_check=1 (2026-10-02): stay on the main menu, log the pzopt menu items, write Screenshots/pzopt-menu.png, open
-- the mod compatibility dialog (PzoptCompatDialog.show), log its text, write
-- Screenshots/pzopt-compat.png, close it and quit. With `--mod pzopt-compat-javafixture` (harness/compat/make-java-fixture.sh)
-- the dialog lists the fixture's jars and the two settings it switches off. compat_choose=performance|compatibility
-- presses that choice's button after the first screenshot, logs the profile state and writes
-- Screenshots/pzopt-compat-chosen.png (it saves the choice: pass --vmarg -Dpzopt.userOptionsFile=<scratch file>).
local compatCheck = nil

local function compatTick()
    local c = compatCheck
    local ms = MainScreen.instance
    if not ms or (ms.delay and ms.delay > 0) or not ms.exitOption or not ms.exitOption:isVisible() then return end
    local now = getTimestampMs()
    if not c.menuMs then
        local names = {}
        for _, child in pairs(ms.bottomPanel:getChildren()) do
            if child.Type == "ISLabel" and child:isVisible() then
                table.insert(names, string.format("%s@%d", tostring(child.name), child:getY()))
            end
        end
        table.sort(names, function(a, b) return tonumber(a:match("@(%d+)$")) < tonumber(b:match("@(%d+)$")) end)
        print("[pzopt-harness] compat check: menu " .. table.concat(names, " | "))
        getCore():TakeFullScreenshot("pzopt-menu.png")
        c.menuMs = now
    elseif not c.openedMs and now - c.menuMs >= 2000 then
        -- the check's dialog (pzopt_mainscreen_compat.lua; the main menu's PZ OPTIMIZATION screen shows the same scan as a page)
        if not PzoptCompatDialog then
            print("[pzopt-harness] compat check: item NOT FOUND, quitting")
            compatCheck = false
            getCore():quit()
            return
        end
        PzoptCompatDialog.show()
        local dlg = PzoptCompatDialog and PzoptCompatDialog.instance
        print("[pzopt-harness] compat check: dialog " .. (dlg and "open" or "NOT OPEN"))
        if dlg then
            print("[pzopt-harness] compat check: profile '" .. tostring(dlg.profileStatus) .. "' restart=" .. tostring(dlg.restart:isVisible()))
            print("[pzopt-harness] compat check: text " .. tostring(dlg.text.text):gsub(" <[^>]*> ", " "):gsub("%s+", " "))
        end
        c.openedMs = now
    elseif c.openedMs and not c.shotMs and now - c.openedMs >= 2000 then
        getCore():TakeFullScreenshot("pzopt-compat.png")
        c.shotMs = now
    elseif c.choose and not c.choseMs and c.shotMs and now - c.shotMs >= 2000 then
        local dlg = PzoptCompatDialog and PzoptCompatDialog.instance
        if dlg then
            dlg.profileButtons[c.choose]:forceClick()
            local p = getPerformance()
            print("[pzopt-harness] compat check: chose " .. c.choose .. "; modProfile now=" .. p:getPzoptOption("modProfile")
                .. " saved=" .. p:getPzoptOptionSaved("modProfile") .. " status='" .. tostring(dlg.profileStatus)
                .. "' restart=" .. tostring(dlg.restart:isVisible()))
        end
        c.choseMs = now
    elseif c.choseMs and not c.chosenShotMs and now - c.choseMs >= 1500 then
        getCore():TakeFullScreenshot("pzopt-compat-chosen.png")
        c.chosenShotMs = now
    elseif c.shotMs and (not c.choose or c.chosenShotMs) and now - (c.chosenShotMs or c.shotMs) >= 2000 then
        if PzoptCompatDialog and PzoptCompatDialog.instance then PzoptCompatDialog.instance:close() end
        compatCheck = false
        print("[pzopt-harness] compat check: done, quitting")
        getCore():quit()
    end
end

local function selectPreview(mo, key)
    local n = 0
    for _, field in ipairs({ "pzoptPreview", "pzoptEnhancementPreview", "pzoptProfilerPreview" }) do
        local pv = mo[field]
        for _, row in ipairs(pv and pv.rows or {}) do
            if row.entry and row.entry.key == key then
                pv:select(row)
                n = n + 1
            end
        end
    end
    return n
end

local function findSearchBox(el, depth)
    if not el or depth > 8 then return nil end
    if el.Type == "ISTextEntryBox" and el.tooltip and string.find(el.tooltip, "Type words from a setting", 1, true) then return el end
    for _, ch in pairs(el.children or {}) do
        local f = findSearchBox(ch, depth + 1)
        if f then return f end
    end
    return nil
end

-- options_io=1 (2026-10-01): the tabs' "Export settings" / "Import settings..." buttons, 1 s after opening: Export (logs
-- the text and whether Zomboid/pzopt/settings-export.ini matches the clipboard), then Import of that text plus IO_CHANGES
-- and an unknown key through the dialog's OK, logs each control before / after and the result dialog; screenshots
-- pzopt-io-{1-export,2-import,3-result}.png. Never presses Apply (nothing is saved) and puts the clipboard back. Pass
-- `--vmarg -Dpzopt.userOptionsFile=<copy>` so the export file lands beside a copy, not in the player's Zomboid/pzopt/.
local IO_CHANGES = { treesInChunkTexture = "false", fogScalePct = "50", overlayStats = "full", spriteFilter = "sharp" }

local function findButton(el, title, depth)
    if not el or depth > 8 then return nil end
    if el.Type == "ISButton" and el.title == title then return el end
    for _, ch in pairs(el.children or {}) do
        local f = findButton(ch, title, depth + 1)
        if f then return f end
    end
    return nil
end

local function optionByKey(mo, key)
    for _, field in ipairs({ "pzoptOptions", "pzoptEnhancementOptions", "pzoptProfilerOptions" }) do
        for _, o in ipairs(mo[field] or {}) do
            if o.pzoptKey == key then return o end
        end
    end
    return nil
end

local function ioLog(s)
    print("[pzopt-harness] options io: " .. s)
end

-- options_profile=<button text, _ for a space> (2026-10-07, the low-end presets): 1 s after the tab opens, press that profile's button on
-- the home page, log every pzopt control that is not at its default ("profile: key=value") and the stock Display options a
-- profile sets, write Screenshots/pzopt-profile.png, 1.5 s later apply them as the Apply button does, log the stock values again ("profile applied: ..."), then quit (via the
-- screenshot step). Give the run a scratch -Dpzopt.userOptionsFile: Apply saves the choice.
local function stockState()
    local core, perf = getCore(), getPerformance()
    local function v(f) local ok, r = pcall(f); return ok and tostring(r) or "?" end
    return "vsync=" .. v(function() return core:getOptionVSync() end)
        .. " framerate=" .. v(function() return perf:getFramerate() end)
        .. " uncapped=" .. v(function() return perf:isFramerateUncapped() end)
        .. " lightFPS=" .. v(function() return perf:getLightingFPS() end)
        .. " uiRenderFPS=" .. v(function() return core:getOptionUIRenderFPS() end)
        .. " textureCompression=" .. v(function() return core:getOptionTextureCompression() end)
end

local function profileStep(mo, name)
    name = string.gsub(name, "_", " ") -- the flag cannot carry spaces through the queue's remote command line
    local b = findButton(mo, name, 0)
    if not b then print("[pzopt-harness] profile: button '" .. name .. "' NOT FOUND"); return end
    b.onclick(b.target, b)
    local n = 0
    for _, field in ipairs({ "pzoptOptions", "pzoptEnhancementOptions" }) do
        for _, o in ipairs(mo[field] or {}) do
            local cur = o.pzoptCurrent and o:pzoptCurrent()
            local okd, def = pcall(function() return getPerformance():getPzoptOptionDefault(o.pzoptKey) end)
            if not okd then def = nil end
            if cur ~= nil and def ~= nil and tostring(cur) ~= tostring(def) then
                print("[pzopt-harness] profile: " .. tostring(o.pzoptKey) .. "=" .. tostring(cur))
                n = n + 1
            end
        end
    end
    local fr = mo.gameOptions:get("framerate")
    local frText = fr and fr.control and fr.control.options and fr.control.options[fr.control.selected]
    print("[pzopt-harness] profile: '" .. name .. "' pressed, " .. n .. " pzopt control(s) off their default; framerate combo '"
        .. tostring(type(frText) == "table" and (frText.text or frText[1]) or frText) .. "'; before apply " .. stockState())
    getCore():TakeFullScreenshot("pzopt-profile.png")
end

local function profileApply(mo)
    local ok, err = pcall(function() mo:apply(false) end)
    print("[pzopt-harness] profile applied" .. (ok and "" or (" (apply error " .. tostring(err) .. ")")) .. ": " .. stockState())
end

-- one step per call, 1.5 s apart; true when done
local function ioStep(mo, io, now)
    if now < (io.at or 0) then return false end
    local s = io.step or 0
    if s == 0 then
        io.clip = Clipboard.getClipboard() or ""
        local b = findButton(mo, "Export settings", 0)
        if not b then ioLog("Export button NOT FOUND"); return true end
        b.onclick(b.target)
        io.text = Clipboard.getClipboard() or ""
        local n = 0
        for line in string.gmatch(io.text, "[^\n]+") do
            if string.match(line, "^[%w_]+=") then n = n + 1 end
            ioLog("  " .. line)
        end
        ioLog("export " .. n .. " keys, " .. #io.text .. " chars, file "
            .. (getPerformance():getPzoptSettingsExportFile() == io.text and "matches the clipboard" or "DIFFERS from the clipboard"))
    elseif s == 1 then
        getCore():TakeFullScreenshot("pzopt-io-1-export.png")
    elseif s == 2 then
        if mo.pzoptTransferModal then mo.pzoptTransferModal:destroy() end
        local extra = { "noSuchKey=1" }
        for k, v in pairs(IO_CHANGES) do
            table.insert(extra, k .. "=" .. v)
            local o = optionByKey(mo, k)
            ioLog("before " .. k .. "=" .. (o and o:pzoptCurrent() or "NO CONTROL"))
        end
        Clipboard.setClipboard(io.text .. table.concat(extra, "\n") .. "\n")
        local b = findButton(mo, "Import settings...", 0)
        if not b then ioLog("Import button NOT FOUND"); return true end
        b.onclick(b.target)
        local m = mo.pzoptTransferModal
        ioLog("import dialog " .. ((m and m.entry) and (#m.entry:getText() .. " chars prefilled") or "NOT OPEN"))
    elseif s == 3 then
        getCore():TakeFullScreenshot("pzopt-io-2-import.png")
    elseif s == 4 then
        local m = mo.pzoptTransferModal
        m:onClick(m.yes)
        local r = mo.pzoptTransferModal
        ioLog("result: " .. string.gsub(tostring(r and r.text), "\n", " | "))
        for k, want in pairs(IO_CHANGES) do
            local o = optionByKey(mo, k)
            local cur = o and o:pzoptCurrent() or "NO CONTROL"
            ioLog("after " .. k .. "=" .. cur .. (cur == want and " ok" or (" WANTED " .. want)))
        end
        ioLog("options changed=" .. tostring(mo.gameOptions.changed))
    elseif s == 5 then
        getCore():TakeFullScreenshot("pzopt-io-3-result.png")
    else
        if mo.pzoptTransferModal then mo.pzoptTransferModal:destroy() end
        Clipboard.setClipboard(io.clip)
        ioLog("done (nothing applied, clipboard restored)")
        return true
    end
    io.step, io.at = s + 1, now + 1500
    return false
end

local function optionsTick()
    local c = optionsCheck
    local ms = MainScreen.instance
    if not ms or (ms.delay and ms.delay > 0) then return end
    local now = getTimestampMs()
    if not c.openedMs then
        local mo = ms.mainOptions
        mo:toUI()
        mo:setVisible(true)
        local names = {}
        for _, v in ipairs(mo.tabs.viewList) do table.insert(names, v.name) end
        local tab = c.tab
        if OLD_TABS[tab] and PzoptOptionsTab then
            c.nav = c.nav or OLD_TABS[tab]
            tab = PzoptOptionsTab
        end
        local found = mo.tabs:activateView(tab)
        print("[pzopt-harness] options: tabs " .. table.concat(names, " | ") .. "; " .. tab .. (found and " shown" or " NOT FOUND"))
        if c.nav and PzoptOptionsNavigate then
            applyNav(mo, c.nav)
            print("[pzopt-harness] options: page " .. c.nav)
        end
        c.openedMs = now
    elseif c.read and not c.readDone then
        if now >= c.openedMs + 1500 then readTick(c, ms, now) end
    elseif c.joy and not c.joyDone then
        if now < (c.joyAt or (c.openedMs + 1500)) then return end
        local i = (c.joyIndex or 0) + 1
        if i > #c.joy then
            c.joyDone, c.shotMs = true, now
            return
        end
        local ok, err = pcall(joyStep, ms.mainOptions, c.joy[i], i)
        print("[pzopt-harness] joy: " .. i .. " " .. c.joy[i] .. (ok and "" or (" ERROR " .. tostring(err))) .. " -> " .. joyDescribe(ms.mainOptions))
        c.joyIndex, c.joyAt = i, now + 600
    elseif c.shots and not c.shotsDone then
        -- one page every 1.5 s, the screenshot 1 s after showing it
        local i = c.shotIndex or 0
        if i == 0 or now >= c.shotAt then
            if i > 0 and not c.shotTaken then
                getCore():TakeFullScreenshot("pzopt-options-" .. i .. ".png")
                print("[pzopt-harness] options: screenshot " .. i .. " " .. c.shots[i])
                c.shotTaken, c.shotAt = true, now + 500
                return
            end
            i = i + 1
            if i > #c.shots then
                c.shotsDone, c.shotMs = true, now
                return
            end
            applyNav(ms.mainOptions, c.shots[i])
            c.shotIndex, c.shotAt, c.shotTaken = i, now + 1000, false
        end
    elseif c.search and not c.searched and now - c.openedMs >= 1000 then
        c.searched = true
        local box = findSearchBox(ms.mainOptions, 0)
        if box then box:setText(c.search) end
        print("[pzopt-harness] options: search '" .. c.search .. "'" .. (box and " typed" or ": search box NOT FOUND"))
    elseif c.profile and not c.profilePressed and now - c.openedMs >= 1000 then
        c.profilePressed = true
        profileStep(ms.mainOptions, c.profile)
    elseif c.profile and not c.profileDone and now - c.openedMs >= 2500 then
        c.profileDone = true
        profileApply(ms.mainOptions)
    elseif c.io and not c.ioDone and now - c.openedMs >= 1000 then
        c.ioDone = ioStep(ms.mainOptions, c.io, now)
    elseif c.select and not c.shotMs and now - c.openedMs >= 1000 and now - c.openedMs < 3000 then
        local n = selectPreview(ms.mainOptions, c.select)
        if not c.selectLogged then
            c.selectLogged = true
            print("[pzopt-harness] options: preview on '" .. c.select .. "' (" .. n .. " page(s))")
        end
    elseif not c.shotMs and now - c.openedMs >= 3000 then
        getCore():TakeFullScreenshot("pzopt-options.png")
        print("[pzopt-harness] options: screenshot requested")
        c.shotMs = now
    elseif c.shotMs and now - c.shotMs >= 2000 then
        optionsCheck = false
        print("[pzopt-harness] options: done, quitting")
        getCore():quit()
    end
end

local function onMainMenuEnter()
    local flags = readFlags()
    if not flags or not flags.mode then return end
    if flags.started then
        print("[pzopt-harness] run finished, quitting to desktop")
        getCore():quit()
        return
    end
    if flags.consumed and not flags.pad then return end -- Continue already triggered by this process (Lua was reset)
    if flags.pad and pad == false then return end -- pad script done, quitting
    if flags.compat_check and flags.compat_check ~= "" then
        if compatCheck == nil then
            appendFlag("consumed=1")
            compatCheck = { choose = (flags.compat_choose and flags.compat_choose ~= "") and flags.compat_choose or nil }
        end
        return
    end
    if flags.mods_shot and flags.mods_shot ~= "" then   -- pzopt_harness_mods.lua drives the Mods screen and quits
        appendFlag("consumed=1")
        return
    end
    if flags.options_tab and flags.options_tab ~= "" then
        if optionsCheck == nil then
            appendFlag("consumed=1")
            local shots = nil
            if flags.options_shots and flags.options_shots ~= "" then
                shots = {}
                for s in string.gmatch(flags.options_shots, "[^;]+") do table.insert(shots, s) end
            end
            optionsCheck = { tab = flags.options_tab, search = flags.options_search ~= "" and flags.options_search or nil,
                nav = flags.options_nav ~= "" and flags.options_nav or nil, shots = shots,
                read = (flags.options_read and flags.options_read ~= "") and {} or nil,
                joy = (flags.options_joy and flags.options_joy ~= "") and (function()
                    local t = {}
                    for s in string.gmatch(flags.options_joy, "[^,]+") do table.insert(t, s) end
                    return t
                end)() or nil,
                select = flags.options_select ~= "" and flags.options_select or nil,
                io = (flags.options_io and flags.options_io ~= "") and {} or nil,
                profile = (flags.options_profile and flags.options_profile ~= "") and flags.options_profile or nil }
        end
        return
    end
    print("[pzopt-harness] mode=" .. tostring(flags.mode) .. " quit_after=" .. tostring(flags.quit_after))
    if flags.menu_check and flags.menu_check ~= "" and MainScreen.instance then
        -- menu_check=1 (2026-09-23): show and hide the main menu's server settings, sandbox, character creation,
        -- multiplayer, credits and spawn screens before the harness presses Continue; logs build time and any error
        -- per screen (the rig of the dropped lazyMenuScreens experiment, kept for menu changes)
        for _, k in ipairs({ "serverSettingsScreen", "sandOptions", "charCreationMain", "multiplayer", "creditsScreen", "mapSpawnSelect" }) do
            local o = MainScreen.instance[k]
            if not o then
                print("[pzopt-harness] menu check: " .. k .. " missing")
            else
                local wasPending = o.pzoptCreatePending
                local t0 = getTimestampMs()
                local ok, err = pcall(function() o:setVisible(true); o:setVisible(false) end)
                print("[pzopt-harness] menu check: " .. k .. " pending=" .. tostring(wasPending) .. " -> built="
                    .. tostring(o.pzoptCreated) .. " in " .. (getTimestampMs() - t0) .. " ms, ok=" .. tostring(ok)
                    .. (ok and "" or (" error " .. tostring(err))))
            end
        end
        local ok2, err2 = pcall(function() return MainScreen.instance.sandOptions:getSandboxPreset() end)
        print("[pzopt-harness] menu check: sandOptions:getSandboxPreset ok=" .. tostring(ok2) .. " " .. tostring(err2))
    end
    pending = flags
end


-- pad=1 (run.sh --pad, 2026-09-23): menu profiling with a virtual pad. The harness stays on the main menu,
-- writes Lua/pzopt-pad-ready.txt once the menu accepts input (run.sh then feeds the pad script), logs every pad
-- handler call ("[pzopt-pad] <handler> t=<epoch ms> ms=<handler ms> <focus before> -> <focus after>"), every menu
-- frame (OnFETick gap) of 25 ms or more, and quits once run.sh appends pad_done=1 to the flag file.
local pad = nil
local function padFocus(jd)
    local f = jd and jd.focus
    if not f then return "none" end
    local s = tostring(f.Type or "?")
    if f.joypadIndexY then s = s .. ":" .. tostring(f.joypadIndexY) end
    if f.joypadIndex then s = s .. "," .. tostring(f.joypadIndex) end
    return s
end
local function padWrap(name)
    local orig = JoypadControllerData[name]
    if type(orig) ~= "function" then return end
    JoypadControllerData[name] = function(self, a, b)
        local before = padFocus(self.joypad)
        local t0 = getTimestampMs()
        orig(self, a, b)
        local t1 = getTimestampMs()
        print("[pzopt-pad] " .. name .. (a ~= nil and ("(" .. tostring(a) .. ")") or "") .. " id=" .. tostring(self.id)
            .. " t=" .. tostring(t0) .. " ms=" .. tostring(t1 - t0) .. " " .. before .. " -> " .. padFocus(self.joypad))
    end
end
local function startPad()
    for _, n in ipairs({ "onPressUp", "onPressDown", "onPressLeft", "onPressRight", "onPressButton", "onReleaseButton",
                         "onPressButtonNoFocus" }) do
        padWrap(n)
    end
    pad = { last = getTimestampMs(), n = 0, check = 0 }
    local w = getFileWriter("pzopt-pad-ready.txt", true, false)
    if w then w:write("ready_epoch_ms=" .. tostring(pad.last) .. "\n"); w:close() end
    print("[pzopt-pad] menu ready t=" .. tostring(pad.last))
end
local function padTick()
    local now = getTimestampMs()
    local gap = now - pad.last
    pad.last = now
    pad.n = pad.n + 1
    if gap >= 25 then print("[pzopt-pad] slow frame t=" .. tostring(now) .. " gap=" .. tostring(gap)) end
    if now >= pad.check then
        pad.check = now + 500
        local flags = readFlags()
        if flags and flags.pad_done then
            print("[pzopt-pad] script done, " .. pad.n .. " menu frames; quitting to desktop")
            pad = false
            getCore():quit()
        end
    end
end

-- OnFETick is the only per-frame event the main menu fires (OnTickEvenPaused is in-world only,
-- which is why earlier versions of this file never pressed Continue by themselves)
local function onFETick()
    if compatCheck then
        local ok, err = pcall(compatTick)
        if not ok then print("[pzopt-harness] compat check: rig error " .. tostring(err)); compatCheck = false; getCore():quit() end
        return
    end
    if optionsCheck then
        local ok, err = pcall(optionsTick)
        if not ok then print("[pzopt-harness] options: rig error " .. tostring(err)); optionsCheck = false; getCore():quit() end
        return
    end
    if pad then padTick() end
    if pending then
        local ms = MainScreen.instance
        -- MainScreen ignores menu actions while its own start-up delay runs; wait for that, nothing more
        if not ms or (ms.delay and ms.delay > 0) then return end
        local flags = pending
        pending = nil
        appendFlag("consumed=1")
        -- quit_after counts from here whether we continue or the game is already loading the save
        if flags.quit_after then
            local secs = tonumber(flags.quit_after)
            if secs then quitAtMs = getTimestampMs() + secs * 1000 end
        end
        if flags.pad then
            startPad()
            return
        end
        if getPlayer() or ms.inGame then
            print("[pzopt-harness] a world is already loading; not continuing")
            return
        end
        if not MainScreen.latestSaveWorld then
            print("[pzopt-harness] no latest save to continue")
            return
        end
        print("[pzopt-harness] continuing latest save " .. tostring(MainScreen.latestSaveWorld) .. " (" .. tostring(MainScreen.latestSaveGameMode) .. ")")
        MainScreen.continueLatestSave(MainScreen.latestSaveGameMode, MainScreen.latestSaveWorld)
        if flags.stock_drive and flags.inputlag == "1" then
            -- no pzopt.Harness presses click-to-start in an uninstalled game: the input driver's script (run.sh
            -- --inputlag) clicks it, so it starts now instead of after the player exists
            local w = getFileWriter("pzopt-inputlag-ready.txt", true, false)
            if w then w:write("ready_epoch_ms=" .. tostring(getTimestampMs()) .. "\n"); w:close() end
            print("[pzopt-stockdrive] loading; the input driver clicks click-to-start")
        end
    end
end

-- lure=<animal type> (2026-09-22, CanSee repro): lure_at seconds after the player exists, spawn the
-- animal 8 tiles away, put a carrot in the primary hand and queue the stock ISLureAnimal action, i.e.
-- the context menu's "Lure" path (lureAnimal -> IsoAnimal.tryLure -> CanSee(IsoMovingObject)). A
-- status line every 2 s: distance, lured count, current action. Stock: the animal walks up to the player.
local lure = nil
local function lureTick()
    if lure == false then return end
    local player = getPlayer()
    if not player then return end
    if lure == nil then
        local flags = readFlags()
        if not flags or not flags.lure or flags.lure == "" then lure = false; return end
        lure = { kind = flags.lure, breed = flags.lure_breed or "holstein",
                 atMs = getTimestampMs() + (tonumber(flags.lure_at) or 10) * 1000 }
    end
    local now = getTimestampMs()
    if not lure.animal then
        if now < lure.atMs then return end
        local sq = player:getCurrentSquare()
        local target = nil
        for _, d in ipairs({ {8, 0}, {-8, 0}, {0, 8}, {0, -8}, {6, 6}, {-6, -6} }) do
            local s = getCell():getGridSquare(sq:getX() + d[1], sq:getY() + d[2], sq:getZ())
            if s and s:isFree(false) then target = s; break end
        end
        if not target then print("[pzopt-harness] lure: no free square 8 tiles from the player"); lure = false; return end
        local breed = AnimalDefinitions.getDef(lure.kind):getBreedByName(lure.breed)
        local animal = addAnimal(getCell(), target:getX(), target:getY(), target:getZ(), lure.kind, breed)
        animal:addToWorld()
        lure.animal = animal
        local item = player:getInventory():AddItem("Base.Carrots")
        player:setPrimaryHandItem(item)
        print("[pzopt-harness] lure: " .. lure.kind .. " at " .. target:getX() .. "," .. target:getY() .. ", player at " .. sq:getX() .. "," .. sq:getY() .. ", queueing ISLureAnimal with " .. item:getFullType())
        ISTimedActionQueue.add(ISLureAnimal:new(player, animal, item))
        lure.logMs = now
        return
    end
    if now - lure.logMs >= 2000 then
        lure.logMs = now
        print(string.format("[pzopt-harness] lure: dist=%.1f lured=%d action=%s", lure.animal:DistTo(player),
            player:getLuredAnimals():size(),
            tostring(ISTimedActionQueue.getTimedActionQueue(player).queue[1] and ISTimedActionQueue.getTimedActionQueue(player).queue[1].Type)))
    end
end

-- glass=R (2026-10-01, puddle reflection flicker with broken glass): glass_at (4) s after the player exists, broken
-- glass (IsoGridSquare.addBrokenGlass, the stock IsoBrokenGlass floor sprite, baked as a MinusFloor object) on every
-- free level-0 square within R tiles of the player, glass_dx / glass_dy (0) tiles away; one log line with the count.
-- With weather=storm thunder_secs=0 puddles=0.8 and a still camera (route=S:1 speed=1 hold=N) the puddles lie over
-- the shards: runs `glass-*`.
local glass = nil
local function glassTick()
    if glass == false then return end
    local player = getPlayer()
    if not player then return end
    if glass == nil then
        local flags = readFlags()
        if not flags or not flags.glass or flags.glass == "" then glass = false; return end
        local win = flags.glass:match("^window([NW]?)$")
        if flags.glass == "find" then win = "" end -- list the candidates (nearest first, up to 12), smash nothing
        glass = { r = win and (tonumber(flags.glass_r) or 40) or (tonumber(flags.glass) or 3), window = win,
                  dx = tonumber(flags.glass_dx) or 0, dy = tonumber(flags.glass_dy) or 0,
                  atMs = getTimestampMs() + (tonumber(flags.glass_at) or 4) * 1000, find = flags.glass == "find",
                  n = tonumber(flags.glass_n) or 1 }
    end
    if getTimestampMs() < glass.atMs then return end
    local sq = player:getCurrentSquare()
    if not sq then return end
    if glass.window then
        -- glass=windowN|windowW: the nearest exterior window of that facing within glass_r (40) tiles on level 0,
        -- smashed (no alarm), broken glass on both of its squares, the player put 3 tiles along the wall on the
        -- outside square so a route E:6 (N) / S:6 (W) walks past it; the route origin is the player's position at
        -- route start, so this teleport must land before it (glass_at)
        local want = glass.window
        local cell = getCell()
        local r = glass.r
        local best, bestD, bestOut, bestWx, bestWy = nil, 1e9, nil, 0, 0
        local fb, fbD, fbOut, fbWx, fbWy = nil, 1e9, nil, 0, 0 -- fallback: any window of the facing with a free opposite square
        local nSq, nWin, nFacing, nShown = 0, 0, 0, 0
        local found = {}
        for x = sq:getX() - r, sq:getX() + r do
            for y = sq:getY() - r, sq:getY() + r do
                local s = cell:getGridSquare(x, y, 0)
                if s then
                    nSq = nSq + 1
                    local objs = s:getObjects()
                    for i = 0, objs:size() - 1 do
                        local o = objs:get(i)
                        if instanceof(o, "IsoWindow") then
                            nWin = nWin + 1
                            local north = o:getNorth()
                            if (want == "" or north == (want == "N")) and not o:isDestroyed() then
                                nFacing = nFacing + 1
                                local opp = cell:getGridSquare(north and x or x - 1, north and y - 1 or y, 0)
                                -- "outside" = the square has puddle geometry (level-0 outdoor squares only; isOutside() and
                                -- getRoom() said false / nil for every square of the Rosewood store, 2026-10-01)
                                local sOut, oOut = s:getPuddles() ~= nil, opp and opp:getPuddles() ~= nil or false
                                local d = (x - sq:getX()) ^ 2 + (y - sq:getY()) ^ 2
                                table.insert(found, { d = d, x = x, y = y, north = north, sOut = sOut, oOut = oOut, o = o })
                                if glass.find then
                                elseif nShown < 4 then
                                    nShown = nShown + 1
                                    print(string.format("[pzopt-harness] glass: window%s %d,%d puddles=%s opposite puddles=%s", north and "N" or "W", x, y,
                                        tostring(sOut), tostring(oOut)))
                                end
                                local out = nil
                                if opp and oOut and not sOut then out = opp
                                elseif opp and sOut and not oOut then out = s end
                                if out and d < bestD then best, bestD, bestOut, bestWx, bestWy = o, d, out, x, y end
                                if opp and d < fbD then fb, fbD, fbOut, fbWx, fbWy = o, d, (sOut and s or opp), x, y end
                            end
                        end
                    end
                end
            end
        end
        print(string.format("[pzopt-harness] glass: scanned %d squares, %d windows, %d facing %s, exterior match=%s", nSq, nWin, nFacing, want, tostring(best ~= nil)))
        if glass.find then
            table.sort(found, function(a, b) return a.d < b.d end)
            for i = 1, math.min(12, #found) do
                local w = found[i]
                local ox, oy = w.x, w.y
                if w.north then oy = oy - 1 else ox = ox - 1 end
                print(string.format("[pzopt-harness] glass: find %2d: window%s %d,%d dist %.0f puddles=%s opposite(%d,%d) puddles=%s", i, w.north and "N" or "W",
                    w.x, w.y, math.sqrt(w.d), tostring(w.sOut), ox, oy, tostring(w.oOut)))
            end
            glass = false
            return
        end
        if not best and fb then best, bestOut, bestWx, bestWy = fb, fbOut, fbWx, fbWy; print("[pzopt-harness] glass: no exterior test match, taking the nearest window of that facing") end
        if not best then print("[pzopt-harness] glass: no window" .. want .. " within " .. r .. " tiles of " .. sq:getX() .. "," .. sq:getY()); glass = false; return end
        best:smashWindow(false, false)
        best:addBrokenGlass(false)
        best:addBrokenGlass(true)
        if glass.n > 1 then
            -- glass_n=N: the N nearest windows of the facing (a shop front), glass on both squares of each
            table.sort(found, function(a, b) return a.d < b.d end)
            local k = 1
            for i = 1, #found do
                if k >= glass.n then break end
                local w = found[i]
                if w.o ~= best and not w.o:isDestroyed() then
                    w.o:smashWindow(false, false); w.o:addBrokenGlass(false); w.o:addBrokenGlass(true); k = k + 1
                    print(string.format("[pzopt-harness] glass: also window%s %d,%d", w.north and "N" or "W", w.x, w.y))
                end
            end
        end
        local north = best:getNorth()
        print(string.format("[pzopt-harness] glass: window%s at %d,%d smashed, glass on both squares, outside square %d,%d (put the route past it with start= / route=)",
            north and "N" or "W", bestWx, bestWy, bestOut:getX(), bestOut:getY()))
        glass = false
        return
    end
    local cx, cy = sq:getX() + glass.dx, sq:getY() + glass.dy
    local n, skipped = 0, 0
    for x = cx - glass.r, cx + glass.r do
        for y = cy - glass.r, cy + glass.r do
            local s = getCell():getGridSquare(x, y, 0)
            if s and s:isFree(false) and s:getBrokenGlass() == nil then
                s:addBrokenGlass(); n = n + 1
            else skipped = skipped + 1 end
        end
    end
    print(string.format("[pzopt-harness] glass: %d squares of broken glass round %d,%d (r=%d), %d skipped", n, cx, cy, glass.r, skipped))
    glass = false
end

-- options_check=S (2026-09-23): S seconds into the world, activate the Optimizations tab of the in-game options
-- screen (built lazily on first activation since the lazy-tab change) through the stock tab path, without
-- showing the screen, and log whether it built, its control count and whether building left the screen
-- "changed" (it must not: Accept would then save untouched values).
local optionsCheck = nil
local function optionsCheckTick()
    if optionsCheck == false then return end
    if not getPlayer() then return end
    if optionsCheck == nil then
        local flags = readFlags()
        if not flags or not flags.options_check or flags.options_check == "" then optionsCheck = false; return end
        optionsCheck = { atMs = getTimestampMs() + (tonumber(flags.options_check) or 10) * 1000 }
    end
    if getTimestampMs() < optionsCheck.atMs then return end
    optionsCheck = false
    local mo = MainScreen.instance and MainScreen.instance.mainOptions
    if not mo then print("[pzopt-harness] options check: no in-game MainOptions"); return end
    local keys = {}
    for _, k in ipairs({"Forward", "Backward", "Left", "Right", "Run", "Interact", "Toggle Inventory", "Aim"}) do
        table.insert(keys, k .. "=" .. tostring(getCore():getKey(k)))
    end
    print("[pzopt-harness] options check: screen pending=" .. tostring(mo.pzoptCreatePending) .. " created=" .. tostring(mo.pzoptCreated)
        .. ", keys " .. table.concat(keys, " "))
    local tui = getTimestampMs()
    mo:toUI() -- opening the screen calls toUI first (MainScreen)
    print("[pzopt-harness] options check: toUI (builds a deferred screen) " .. (getTimestampMs() - tui) .. " ms, tabs=" .. tostring(mo.tabs ~= nil)
        .. " pending=" .. tostring(mo.pzoptCreatePending))
    local before = mo.pzoptBuilt
    local nBefore = #mo.gameOptions.options
    local t0 = getTimestampMs()
    mo.tabs:activateView(PzoptOptionsTab or "Optimizations")
    print(string.format("[pzopt-harness] options check: built before=%s after=%s in %d ms, controls=%d, game options %d -> %d, changed=%s, active=%s",
        tostring(before), tostring(mo.pzoptBuilt), getTimestampMs() - t0, mo.pzoptOptions and #mo.pzoptOptions or -1,
        nBefore, #mo.gameOptions.options, tostring(mo.gameOptions.changed), tostring(mo.tabs:getActiveView() == mo.pzoptPanel)))
    mo.tabs:activateView(PzoptOptionsTab or "Optimizations") -- a second activation must not build again
    print("[pzopt-harness] options check: second activation, game options " .. #mo.gameOptions.options)
end

-- pause_menu=S (2026-09-23): S seconds into the world, open the pause menu the way Esc does (ToggleEscapeMenu),
-- close it pause_menu_secs (5) later. pause_menu_cap=<fps> sets the "Menu framerate" combo to that entry for the
-- rig and puts the player's choice back when the menu closes. The frame cap's own console line ("frame cap: menu
-- phase 5.0 s, 300 frames, 60.0 fps (cap 60 fps)") is the measurement: the pause menu runs at the menu cap.
local pauseMenu = nil
local function escapeKey()
    local k = getCore():getKey("Main Menu")
    return k ~= 0 and k or Keyboard.KEY_ESCAPE
end
local function pauseMenuTick()
    if pauseMenu == false then return end
    if not getPlayer() then return end
    if pauseMenu == nil then
        local flags = readFlags()
        if not flags or not flags.pause_menu or flags.pause_menu == "" then pauseMenu = false; return end
        pauseMenu = { openMs = getTimestampMs() + (tonumber(flags.pause_menu) or 10) * 1000,
            secs = tonumber(flags.pause_menu_secs) or 5, cap = tonumber(flags.pause_menu_cap) }
    end
    local now = getTimestampMs()
    if not pauseMenu.closeMs and now >= pauseMenu.openMs then
        local perf = getPerformance()
        if pauseMenu.cap then
            pauseMenu.oldIndex = perf:getMenuFramerateIndex()
            local fpsTable = { 500, 430, 400, 330, 300, 244, 240, 165, 144, 120, 95, 90, 75, 60, 55, 45, 30, 24 } -- FrameCap.FPS_TABLE
            for i, fps in ipairs(fpsTable) do
                if fps == pauseMenu.cap then perf:setMenuFramerateIndex(i + 2) end
            end
        end
        ToggleEscapeMenu(escapeKey())
        pauseMenu.closeMs = now + pauseMenu.secs * 1000
        print("[pzopt-harness] pause menu: open=" .. tostring(MainScreen.instance and MainScreen.instance:isVisible())
            .. " menu cap index=" .. tostring(perf:getMenuFramerateIndex()))
    elseif pauseMenu.closeMs and not pauseMenu.closed and now >= pauseMenu.closeMs then
        if MainScreen.instance and MainScreen.instance:isVisible() then
            ToggleEscapeMenu(escapeKey())
        end
        pauseMenu.closed = true
        print("[pzopt-harness] pause menu: closed, open=" .. tostring(MainScreen.instance and MainScreen.instance:isVisible()))
    elseif pauseMenu.closed and now >= pauseMenu.closeMs + 1000 then
        -- a second later, so the frame cap's phase line still names the rig's cap
        if pauseMenu.oldIndex then getPerformance():setMenuFramerateIndex(pauseMenu.oldIndex) end
        pauseMenu = false
    end
end

-- inputlag=1 (run.sh --inputlag, 2026-09-24): in-game input-lag profile. 6 s after the player exists this writes
-- Lua/pzopt-inputlag-ready.txt (run.sh then feeds the driver script); inputlag_pad=1 in the flag file gives the
-- driver's virtual Xbox 360 pad to player 1 the way JoypadState.onGameStart does for a menu pad; inputlag_done=1 quits.
local INPUTLAG_GUID = "030000005e0400008e02000010010000"
local inputLag = nil
local function inputLagBindPad()
    local id = nil
    for i = 0, getControllerCount() - 1 do
        if isControllerConnected(i) and getControllerGUID(i) == INPUTLAG_GUID then id = i end
    end
    if not id then print("[pzopt-inputlag] no connected controller with GUID " .. INPUTLAG_GUID); return end
    local controller = JoypadState.controllers[id]
    local joypadData = JoypadState.joypads[1]
    joypadData:setActive(true)
    controller:setJoypad(joypadData)
    joypadData.inMainMenu = false
    joypadData.focus = nil
    joypadData.player = 0
    JoypadState.players[1] = joypadData
    local playerObj = getSpecificPlayer(0)
    setPlayerJoypad(0, joypadData.id, playerObj, nil, false)
    getPlayerInventory(0):setController(joypadData.id)
    getPlayerLoot(0):setController(joypadData.id)
    print("[pzopt-inputlag] pad (controller " .. id .. ") bound to player 1, joypad bind " .. tostring(playerObj:getJoypadBind()))
end
local function inputLagTick()
    if inputLag == false then return end
    if not getPlayer() then return end
    local now = getTimestampMs()
    if inputLag == nil then
        local flags = readFlags()
        if not flags or flags.inputlag ~= "1" then inputLag = false; return end
        if flags.stock_drive then inputLag = { ready = true, check = 0, stock = true } -- the drive stays seated (stockDriveTick)
        else inputLag = { readyMs = now + 6000, check = 0 } end
    end
    local player = getPlayer()
    if player:getVehicle() and not inputLag.stock then
        -- the bench save starts in the drive bench's car: W/S would be throttle / brake. Get out on foot first
        if not inputLag.exiting then
            inputLag.exiting = true
            ISTimedActionQueue.add(ISExitVehicle:new(player))
            print("[pzopt-inputlag] player is in a vehicle; exiting it before the script")
        end
        inputLag.readyMs = now + 3000
        return
    end
    if not inputLag.ready and now >= inputLag.readyMs then
        inputLag.ready = true
        local w = getFileWriter("pzopt-inputlag-ready.txt", true, false)
        if w then w:write("ready_epoch_ms=" .. tostring(now) .. "\n"); w:close() end
        print("[pzopt-inputlag] world ready t=" .. tostring(now))
    end
    if now < inputLag.check then return end
    inputLag.check = now + 250
    local flags = readFlags()
    if not flags then return end
    if flags.inputlag_pad and not inputLag.bound then
        inputLag.bound = true
        local ok, err = pcall(inputLagBindPad)
        if not ok then print("[pzopt-inputlag] pad bind error " .. tostring(err)) end
    end
    if flags.inputlag_done then
        print("[pzopt-inputlag] script done, quitting")
        inputLag = false
        getCore():quit()
    end
end

-- stock_drive=1 (2026-10-18, the car flicker on a truly uninstalled game: `--install stock --inputlag
-- harness/inputlag/stock-click.txt`): the drive mode in Lua with the game's own calls only, since pzopt.Harness is not
-- there. On the bench save (the player stands at the KY-60 path start, 8010,11204): stock_drive_hour (1) o'clock,
-- weather stopped, grid power on and the light switches within 50 tiles on (Scene.lightsOnAround), a Base.RaceCar12
-- spawned facing east with headlights on and the player seated, the camera zoomed out, then the game's cruise control
-- (setRegulator) at stock_drive_kmh (120) with no steering: the road runs straight east. A line every second
-- ([pzopt-stockdrive] t= pos= kmh= yaw=); quits after stock_drive_secs (35) of driving.
local stockDrive = nil
local function stockDriveTick()
    if stockDrive == false then return end
    local player = getPlayer()
    if not player then return end
    local now = getTimestampMs()
    if stockDrive == nil then
        local flags = readFlags()
        if not flags or not flags.stock_drive then stockDrive = false; return end
        stockDrive = { t0 = now, step = 0, hour = tonumber(flags.stock_drive_hour) or 1,
            kmh = tonumber(flags.stock_drive_kmh) or 120, secs = tonumber(flags.stock_drive_secs) or 35, logMs = 0 }
        appendFlag("started=1") -- back at the menu = the run is over (the menu quits to the desktop)
    end
    local s = stockDrive
    local dt = now - s.t0
    if s.step == 0 then
        s.step = 1
        local gt = getGameTime()
        gt:setLastTimeOfDay(s.hour); gt:setTimeOfDay(s.hour)
        getClimateManager():stopWeatherAndThunder()
        getWorld():setHydroPowerOn(true)
        local shut = math.ceil(getWorld():getWorldAgeDays()) + 30
        local opt = getSandboxOptions():getOptionByName("ElecShutModifier")
        if opt:getValue() < shut then opt:setValue(shut) end
        local on, px, py = 0, math.floor(player:getX()), math.floor(player:getY())
        for z = 0, 3 do for y = py - 50, py + 50 do for x = px - 50, px + 50 do
            local sq = getCell():getGridSquare(x, y, z)
            if sq then
                local objs = sq:getObjects()
                for i = 0, objs:size() - 1 do
                    local o = objs:get(i)
                    if instanceof(o, "IsoLightSwitch") and not o:isActivated() then o:setActive(true, false, true); on = on + 1 end
                end
            end
        end end end
        print(string.format("[pzopt-stockdrive] hour %.1f, weather stopped, hydro power on, ElecShutModifier %d, %d switches on, player at %d,%d,%d vehicle=%s",
            s.hour, opt:getValue(), on, px, py, math.floor(player:getZ()), tostring(player:getVehicle())))
    elseif s.step == 1 and dt >= 2000 then
        s.step = 2
        local v = player:getVehicle()
        if not v then
            local sq = getCell():getGridSquare(math.floor(player:getX()), math.floor(player:getY()), 0)
            v = addVehicleDebug("Base.RaceCar12", IsoDirections.E, 0, sq)
            if not v then print("[pzopt-stockdrive] could not spawn the car"); stockDrive = false; return end
            v:repair()
            player:getInventory():AddItem(v:createVehicleKey())
            v:setAngles(0, 90, 0) -- addVehicleDebug adds up to 11 deg of random heading; the road runs due east
            if not v:enter(0, player) then print("[pzopt-stockdrive] could not seat the player"); stockDrive = false; return end
        end
        s.v = v
        v:setHeadlightsOn(true)
        print("[pzopt-stockdrive] car " .. tostring(v:getScriptName()) .. " at " .. string.format("%.1f,%.1f", v:getX(), v:getY()) .. ", player seated " .. tostring(player:getVehicle() == v))
    elseif s.step == 2 and dt >= 4000 then
        s.step = 3
        local v = s.v
        v:setAngles(0, 90, 0)
        v:setKeysInIgnition(true)
        v:engineDoRunning()
        for _ = 1, 12 do getCore():doZoomScroll(0, 1) end
        v:setRegulator(true)
        v:setRegulatorSpeed(s.kmh)
        s.driveMs = now
        print("[pzopt-stockdrive] drive start epoch_ms=" .. tostring(now) .. " engine=" .. tostring(v:isEngineRunning()) .. " regulator " .. s.kmh)
    elseif s.step == 3 then
        local v = s.v
        if now - s.logMs >= 1000 then
            s.logMs = now
            print(string.format("[pzopt-stockdrive] t=%.0f pos=%.1f,%.1f kmh=%.1f yaw=%.1f headlights=%s zoom=%.2f epoch_ms=%d",
                (now - s.driveMs) / 1000, v:getX(), v:getY(), v:getCurrentSpeedKmHour(), v:getAngleY(), tostring(v:getHeadlightsOn()),
                getCore():getZoom(0), now))
        end
        if now - s.driveMs >= s.secs * 1000 then
            print("[pzopt-stockdrive] done, quitting")
            stockDrive = false
            getCore():quit()
        end
    end
end

-- world_map=<s> (2026-09-27, Workshop report "CTD when opening the map or at max zoom"): <s> seconds after the player
-- exists open the world map, then sweep its zoom (setZoom, no easing) from the maximum down to the base zoom (fully
-- zoomed out), back up and down again in world_map_steps (24) steps of world_map_step_ms (250) each, logging
-- [pzopt-map] zoom=; it stays fully zoomed out until world_map_secs (30) after opening, then closes the map.
local worldMap = nil
local function worldMapTick()
    if worldMap == false then return end
    if not getPlayer() then return end
    local now = getTimestampMs()
    if worldMap == nil then
        local flags = readFlags()
        if not flags or not flags.world_map or flags.world_map == "" then worldMap = false; return end
        worldMap = { openMs = now + (tonumber(flags.world_map) or 8) * 1000, secs = tonumber(flags.world_map_secs) or 30,
            stepMs = tonumber(flags.world_map_step_ms) or 250, steps = tonumber(flags.world_map_steps) or 24, i = 0 }
    end
    if not worldMap.opened then
        if now < worldMap.openMs then return end
        worldMap.opened = true
        print("[pzopt-map] opening world map, allowed=" .. tostring(ISWorldMap.IsAllowed()))
        ISWorldMap.ShowWorldMap(0)
        worldMap.closeMs = now + worldMap.secs * 1000
        worldMap.nextMs = now + 1000
        print("[pzopt-map] open=" .. tostring(ISWorldMap_instance and ISWorldMap_instance:isVisible()))
        return
    end
    local ui = ISWorldMap_instance
    if not ui then return end
    if now >= worldMap.closeMs then
        ui:close()
        print("[pzopt-map] closed")
        worldMap = false
        return
    end
    local n = worldMap.steps
    if now < worldMap.nextMs or worldMap.i > 3 * n then return end
    worldMap.nextMs = now + worldMap.stepMs
    local base, max = ui.mapAPI:getBaseZoom(), 18.0
    -- 0..n: max -> base, n..2n: base -> max, 2n..3n: max -> base
    local i = worldMap.i
    local t = (i <= n) and (i / n) or ((i <= 2 * n) and (1 - (i - n) / n) or ((i - 2 * n) / n))
    ui.mapAPI:setZoom(max + (base - max) * t)
    worldMap.i = i + 1
    print("[pzopt-map] step " .. i .. " zoom=" .. string.format("%.3f", ui.mapAPI:getZoomF()) .. " base=" .. string.format("%.3f", base))
end

-- pzmenu_check=S (2026-10-18): S seconds into the world (the bench save), the PZ Optimization menu over the pause menu:
-- Esc opens the pause menu, its PZ OPTIMIZATION item opens the screen, the Profiler's overlay toggle (live) and a live
-- Profiler key change at once, the screen closes back onto the pause menu, Esc resumes; every step is a [pzopt-harness]
-- pzmenu line and an in-game screenshot (Screenshots/pzopt-pzmenu-<n>.png). With pzmenu_read=1 the same screens are the
-- readability tour's stops (BENCHREAD lines + Screenshots/pzopt-bench-ui-read-*.png, harness/benchmark-readability.py).
local pzmenu = nil
local PZMENU_KEY = "overlayGraph" -- a live Profiler key (Profiler > Performance overlay > Overlay)
local function pzmenuTick()
    if pzmenu == false then return end
    if not getPlayer() then return end
    if pzmenu == nil then
        local flags = readFlags()
        if not flags or not flags.pzmenu_check or flags.pzmenu_check == "" then pzmenu = false; return end
        pzmenu = { at = getTimestampMs() + (tonumber(flags.pzmenu_check) or 10) * 1000, step = 0, read = flags.pzmenu_read == "1" }
    end
    local now = getTimestampMs()
    if now < pzmenu.at then return end
    local ms = MainScreen.instance
    local function log(t) print("[pzopt-harness] pzmenu: " .. t) end
    local function shot(n) getCore():TakeFullScreenshot("pzopt-pzmenu-" .. n .. ".png") end
    local st = pzmenu.step
    pzmenu.step = st + 1
    pzmenu.at = now + 2500
    if pzmenu.read and st == 0 then
        -- the readability tour over the pause menu (its stops; the tour logs and shoots each one). The "ig-" names keep its
        -- screenshots (Screenshots/pzopt-bench-ui-read-NN-<name>.png) apart from a main-menu tour's on the same machine
        local stops = {
            { name = "ig-pause", fn = function() ToggleEscapeMenu(escapeKey()) ms.bottomPanel.pzoptReadRoot = true end },
            { name = "ig-pzmenu", fn = function()
                ms.bottomPanel.pzoptReadRoot = nil
                ms.pzoptMenuOption.onMouseDown(ms.pzoptMenuOption, 0, 0)
                PzoptMainMenu.instance().forceHints = true
            end },
            { name = "ig-pzmenu-tools-overlay-1", fn = function() PzoptMainMenu.instance():select("tools/overlay/1") end },
            { name = "ig-pzmenu-perf-drive-1", fn = function() PzoptMainMenu.instance():select("perf/drive/1") end },
            { name = "ig-pzmenu-restart-prompt", fn = function() PzoptMainMenu.instance():devStep("vehicleSmooth", 1) end },
            { name = "ig-pzmenu-restart-later", fn = function() PzoptMainMenu.instance():hideRestartPrompt() end },
            { name = "ig-pzmenu-bench-setup", fn = function()
                local m = PzoptMainMenu.instance()
                m.forceNoPrompt = true
                m:devStep("vehicleSmooth", -1)
                m.forceNoPrompt = nil
                m:select("bench/setup")
            end },
            { name = "ig-pzmenu-help", fn = function() PzoptMainMenu.instance():setHelp(true) end },
            { name = "ig-pause-again", fn = function()
                local m = PzoptMainMenu.instance()
                m:setHelp(false)
                m:close()
                ms.bottomPanel.pzoptReadRoot = true
            end },
            { name = "ig-resumed", fn = function()
                ms.bottomPanel.pzoptReadRoot = nil
                ToggleEscapeMenu(escapeKey()) -- the game runs again (quit_after then quits to the desktop, not to the main menu)
                print("[pzopt-harness] pzmenu: resumed, pause menu open=" .. tostring(ms:isVisible()))
            end },
        }
        PzoptBenchRead.begin(stops)
        pzmenu = false
        log("readability tour over the pause menu started (" .. #stops .. " stops)")
        return
    end
    if st == 0 then
        ToggleEscapeMenu(escapeKey())
        log("pause menu open=" .. tostring(ms and ms:isVisible()) .. " item=" .. tostring(ms and ms.pzoptMenuOption and ms.pzoptMenuOption.name))
        shot(1)
    elseif st == 1 then
        ms.pzoptMenuOption.onMouseDown(ms.pzoptMenuOption, 0, 0)
        local m = PzoptMainMenu.instance()
        log("screen open=" .. tostring(m ~= nil) .. " page=" .. tostring(m and m.selected) .. " in game=" .. tostring(ms.inGame))
        shot(2)
    elseif st == 2 then
        local m = PzoptMainMenu.instance()
        m:select("tools/overlay/1")
        local p = getPerformance()
        log("overlay before: visible=" .. tostring(p:isPzoptOverlayVisible()) .. " sampling=" .. tostring(p:isPzoptOverlaySampling()))
        m:activateItem(1) -- the toggle row (first on the page): shows the overlay (and switches the measuring on if it was off)
        log("overlay after the toggle: visible=" .. tostring(p:isPzoptOverlayVisible()) .. " sampling=" .. tostring(p:isPzoptOverlaySampling()))
        pzmenu.keyBefore = p:getPzoptOption(PZMENU_KEY)
        m:devStep(PZMENU_KEY, 1)
        log("live key " .. PZMENU_KEY .. ": " .. tostring(pzmenu.keyBefore) .. " -> " .. p:getPzoptOption(PZMENU_KEY) .. " (in force now)")
    elseif st == 3 then
        shot(3) -- the overlay over the menu, a few seconds into its measuring
        log("overlay showing: visible=" .. tostring(getPerformance():isPzoptOverlayVisible()))
    elseif st == 4 then
        local m = PzoptMainMenu.instance()
        m:devStep(PZMENU_KEY, -1)
        m:close()
        log("screen closed: screen=" .. tostring(PzoptMainMenu.instance() ~= nil) .. " pause menu open=" .. tostring(ms:isVisible())
            .. " " .. PZMENU_KEY .. "=" .. getPerformance():getPzoptOption(PZMENU_KEY))
        shot(4)
    elseif st == 5 then
        ToggleEscapeMenu(escapeKey())
        log("resumed: pause menu open=" .. tostring(ms:isVisible()))
    elseif st == 6 then
        getPerformance():togglePzoptOverlay()
        log("done (overlay visible=" .. tostring(getPerformance():isPzoptOverlayVisible()) .. ")")
        pzmenu = false
    end
end

local function onTickEvenPaused()
    local okz, errz = pcall(pzmenuTick)
    if not okz then print("[pzopt-harness] pzmenu: rig error " .. tostring(errz)); pzmenu = false end
    local ok5, err5 = pcall(worldMapTick)
    if not ok5 then print("[pzopt-map] rig error " .. tostring(err5)); worldMap = false end
    local ok4, err4 = pcall(inputLagTick)
    if not ok4 then print("[pzopt-inputlag] rig error " .. tostring(err4)); inputLag = false end
    local ok6, err6 = pcall(stockDriveTick)
    if not ok6 then print("[pzopt-stockdrive] rig error " .. tostring(err6)); stockDrive = false; getCore():quit() end
    local ok3, err3 = pcall(pauseMenuTick)
    if not ok3 then print("[pzopt-harness] pause menu: rig error " .. tostring(err3)); pauseMenu = false end
    local okg, errg = pcall(glassTick)
    if not okg then print("[pzopt-harness] glass: rig error " .. tostring(errg)); glass = false end
    local ok, err = pcall(lureTick)
    if not ok then print("[pzopt-harness] lure: rig error " .. tostring(err)); lure = false end
    local ok2, err2 = pcall(optionsCheckTick)
    if not ok2 then print("[pzopt-harness] options check: rig error " .. tostring(err2)); optionsCheck = false end
    if quitAtMs and getTimestampMs() >= quitAtMs then
        quitAtMs = nil
        print("[pzopt-harness] quit_after reached, quitting")
        -- from the pause menu getCore():quit() goes to the main menu instead of the desktop (a run waited for an exit that
        -- never came, 2026-10-18): the PZ Optimization screen and the pause menu close first, then the game quits
        pcall(function()
            local m = PzoptMainMenu and PzoptMainMenu.instance and PzoptMainMenu.instance()
            if m then m:close("quit_after") end
            local ms = MainScreen.instance
            if ms and ms.inGame and ms:isVisible() then ToggleEscapeMenu(escapeKey()) end
        end)
        getCore():quit()
    end
end

-- lua_wrap=<table>[,<table>] (2026-09-23): wrap every function of those global tables with a millisecond timer and
-- log each call over 2 ms (nested calls are logged too, innermost first). The Lua event profile (luaEventProfile) names
-- the slow handler; this splits a handler's own plain Lua calls. Installed at boot, so load-time calls are covered.
local function installLuaWrap()
    local flags = readFlags()
    if not flags or not flags.lua_wrap or flags.lua_wrap == "" then return end
    for name in string.gmatch(flags.lua_wrap, "[^,]+") do
        local t = _G[name]
        if name == "ISUIElement" or name == "ISPanel" or name == "ISBaseObject" or name == "ISPanelJoypad" then
            -- every screen inherits these: wrapping them broke the main menu build (2026-09-23, flip-menuwrap2)
            print("[pzopt-harness] lua_wrap: refusing base UI class " .. name)
        elseif type(t) ~= "table" then
            print("[pzopt-harness] lua_wrap: no global table " .. name)
        else
            local n = 0
            for k, v in pairs(t) do
                if type(v) == "function" then
                    local fname = name .. "." .. tostring(k)
                    t[k] = function(...)
                        local t0 = getTimestampMs()
                        local r = { v(...) }
                        local ms = getTimestampMs() - t0
                        if ms >= 2 then print("[pzopt-harness] lua_wrap: " .. fname .. " " .. ms .. " ms") end
                        return unpack(r)
                    end
                    n = n + 1
                end
            end
            print("[pzopt-harness] lua_wrap: " .. n .. " functions of " .. name .. " wrapped")
        end
    end
end
Events.OnGameBoot.Add(installLuaWrap)

-- lua_prof=<table>[,<table>] (2026-09-23, menu profiling): the per-frame methods (prerender, render, update, draw*,
-- onJoypad*, pick, select, layout*) of those global tables timed and summed; every 2 s one console line per busy
-- method: "[pzopt-luaprof] t=<epoch ms> frames=<n> <Table.method> <ms per frame> <calls per frame>". Millisecond
-- stamps, but summed over thousands of calls the rounding averages out. Inclusive: a method that calls another
-- wrapped one counts both. Instance-level functions (o.prerender = ...) are not seen.
local luaProf = nil
local PROF_NAMES = { prerender = true, render = true, update = true, pick = true, select = true }
local function profName(fname)
    return PROF_NAMES[fname] or fname:match("^draw") or fname:match("^onJoypad") or fname:match("^layout")
        or fname:match("^relayout")
end
local function installLuaProf()
    local flags = readFlags()
    if not flags or not flags.lua_prof or flags.lua_prof == "" then return end
    luaProf = { acc = {}, frames = 0, next = getTimestampMs() + 2000 }
    local n = 0
    for name in string.gmatch(flags.lua_prof, "[^,]+") do
        local t = _G[name]
        if type(t) == "table" then
            local fns = {}
            for fname, fn in pairs(t) do
                if type(fn) == "function" and type(fname) == "string" and profName(fname) then fns[fname] = fn end
            end
            for fname, fn in pairs(fns) do
                local key = name .. "." .. fname
                local a = { 0, 0 }
                luaProf.acc[key] = a
                t[fname] = function(...)
                    local t0 = getTimestampMs()
                    local r1, r2, r3 = fn(...)
                    a[1] = a[1] + (getTimestampMs() - t0)
                    a[2] = a[2] + 1
                    return r1, r2, r3
                end
                n = n + 1
            end
        else
            print("[pzopt-luaprof] no global table " .. name)
        end
    end
    print("[pzopt-luaprof] " .. n .. " methods wrapped")
end
local function luaProfTick()
    if not luaProf then return end
    luaProf.frames = luaProf.frames + 1
    local now = getTimestampMs()
    if now < luaProf.next then return end
    luaProf.next = now + 2000
    local f = luaProf.frames
    luaProf.frames = 0
    local rows = {}
    for k, a in pairs(luaProf.acc) do
        if a[1] > 0 or a[2] > 0 then table.insert(rows, { k, a[1], a[2] }) end
        a[1] = 0; a[2] = 0
    end
    table.sort(rows, function(x, y) return x[2] > y[2] end)
    for i = 1, math.min(#rows, 25) do
        local r = rows[i]
        print(string.format("[pzopt-luaprof] t=%d frames=%d %s %.3f %.1f", now, f, r[1], r[2] / math.max(f, 1), r[3] / math.max(f, 1)))
    end
end
Events.OnGameBoot.Add(installLuaProf)
Events.OnFETick.Add(luaProfTick)

Events.OnMainMenuEnter.Add(onMainMenuEnter)
Events.OnFETick.Add(onFETick)
Events.OnTickEvenPaused.Add(onTickEvenPaused)
