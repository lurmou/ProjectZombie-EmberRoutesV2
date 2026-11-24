local EmberUI = require "EmberUI"
local Theme = EmberUI.Theme
local Chrome = EmberUI.Chrome or (EmberUI.Core and EmberUI.Core.Chrome) or require "EmberUI/Core/Chrome"
local Text = EmberUI.Text or (EmberUI.Core and EmberUI.Core.Text) or require "EmberUI/Core/Text"
local PageLifecycle = EmberUI.PageLifecycle or (EmberUI.Core and EmberUI.Core.PageLifecycle) or require "EmberUI/Core/PageLifecycle"
local StatusVisual = EmberUI.StatusVisual or require "EmberUI/Widgets/StatusVisual"

local Kernel = require "EmberRoutes/Kernel"
local SquadService = require "EmberRoutes/Squad/SquadService"
local NpcRuntime = require "EmberRoutes/Npc/NpcRuntime"
local NpcInventoryPanel = require "EmberRoutes/UI/NpcInventoryPanel"
local NpcMedicalPanel = require "EmberRoutes/UI/NpcMedicalPanel"
local ZoneEditorPanel = require "EmberRoutes/UI/ZoneEditorPanel"

local SquadHUD = {}

if Theme and Theme.registerModule then
    Theme.registerModule({
        id = "er.squad",
        labelKey = "IGUI_ER_ThemeMod_Squad",
        label = "Squad Tactical HUD",
        order = 30,
        colors = {
            { key = "hpHigh",  labelKey = "IGUI_ER_ThemeCol_SquadHpHigh", label = "HP Bar High",  default = "success" },
            { key = "hpMid",   labelKey = "IGUI_ER_ThemeCol_SquadHpMid",  label = "HP Bar Mid",   default = "warning" },
            { key = "hpLow",   labelKey = "IGUI_ER_ThemeCol_SquadHpLow",  label = "HP Bar Low",   default = "danger" },
            { key = "overrideBadge", labelKey = "IGUI_ER_ThemeCol_SquadOverride", label = "Personal Override Accent", default = "gold" },
        },
    })
end

local LAYOUT = {
    WIN_W = 296,
    TITLE_H = 26,
    SUMMARY_H = 24,
    PAD_X = 6,
    INNER_W = 284,
    BTN_H = 24,
    BTN_GAP = 4,
    QUICK_PAD_TOP = 6,
    NOTICE_H = 20,
    CARD_H = 62,
    CARD_GAP = 4,
    MAX_VISIBLE = 4,
    EMPTY_H = 68,
    FOOTER_PAD = 6,
    PORTRAIT_W = 40,
    PORTRAIT_H = 46,
    SCROLLBAR_W = 6,
}

local COLORS_STATIC = {
    TEXT_LIGHT = { r = 0.96, g = 0.98, b = 1.0, a = 1.0 },
    TEXT_DIM = { r = 0.58, g = 0.65, b = 0.72, a = 0.95 },
    PORTRAIT_TINT = { r = 0.72, g = 0.80, b = 0.88, a = 0.90 },
    PORTRAIT_DIM = { r = 0.45, g = 0.50, b = 0.56, a = 0.65 },
    AVATAR_HEAD_DIM = { r = 0.21, g = 0.26, b = 0.32, a = 0.78 },
    AVATAR_BODY_DIM = { r = 0.14, g = 0.19, b = 0.24, a = 0.78 },
    DRAG_LINE = { r = 0.35, g = 0.82, b = 1.0, a = 0.95 },
}

local state = {
    activePanel = nil,
    activeScope = nil,
    previewPanels = {},
    noticeText = nil,
    noticeIsError = false,
    noticeExpiresMs = 0,
    unsubService = nil,
}

local function nowMs()
    if type(getTimestampMs) == "function" then
        return getTimestampMs()
    end
    if EmberRoutesJava and EmberRoutesJava.diagnosticTimeNanos then
        return EmberRoutesJava.diagnosticTimeNanos() / 1000000.0
    end
    return 0
end

local function fitOneLine(str, maxW, font)
    local s = tostring(str or "")
    if s == "" or not Text or not Text.wrapLimited then
        return s
    end
    return Text.wrapLimited(s, math.max(12, maxW), font or UIFont.Small, 1)[1] or s
end

local function measureOneLine(str, font)
    local s = tostring(str or "")
    if type(getTextManager) == "function" then
        local tm = getTextManager()
        if tm and tm.MeasureStringX then
            return tm:MeasureStringX(font or UIFont.Small, s)
        end
    end
    return #s * 7
end

local function resolvedColor(col, fallback)
    return col or fallback or COLORS_STATIC.TEXT_LIGHT
end

local function makeBadgeSpec(label, bgCol, borderCol, textCol, font)
    local f = font or UIFont.Small
    local tw = measureOneLine(label, f)
    return {
        label = label,
        w = math.max(24, math.floor(tw + 10)),
        bg = bgCol,
        border = borderCol,
        text = textCol or resolvedColor(Theme.textColor),
    }
end

local function resolveActivityStyle(activityState)
    if activityState == "RIDING" then
        return Theme.selected or Theme.primary, Theme.borderFocus or Theme.accent, Theme.textColor
    elseif activityState == "BOARDING" or activityState == "EXITING" then
        return Theme.badgeBg or Theme.well, Theme.warning or Theme.gold, Theme.warning or Theme.gold
    elseif activityState == "HOLDING" or activityState == "WAIT_VEHICLE" then
        return Theme.badgeBg or Theme.well, Theme.gold or Theme.warning, Theme.gold or Theme.warning
    elseif activityState == "FOLLOWING" then
        return Theme.badgeBg or Theme.well, Theme.success or Theme.accent, Theme.success or Theme.accent
    elseif activityState == "OFFSCREEN" then
        return Theme.well or Theme.panel, Theme.borderSubtle or Theme.border, Theme.muted or COLORS_STATIC.TEXT_DIM
    end
    return Theme.badgeBg or Theme.well, Theme.borderSubtle or Theme.border, Theme.muted or Theme.textColor
end

local function resolveModColor(key, fallback)
    if Theme and Theme.getModuleColor then
        local c = Theme.getModuleColor("er.squad", key)
        if c then
            return c
        end
    end
    return fallback
end

local panelClass = nil

local SquadHudProto = {}

function SquadHudProto:initialise()
    if ISPanel and ISPanel.initialise then
        ISPanel.initialise(self)
    end
end

local function appendQuickBtnRow(v, specs, yPos, btnW, labelPad, smallH)
    for i = 1, #specs do
        local spc = specs[i]
        local bx = LAYOUT.PAD_X + (i - 1) * (btnW + LAYOUT.BTN_GAP)
        local txt = fitOneLine(spc.lbl, btnW - labelPad, UIFont.Small)
        local bg = spc.active and resolvedColor(Theme.primary or Theme.selected) or resolvedColor(Theme.control or Theme.panel)
        local bc = spc.warn and v.warningCol or (spc.active and resolvedColor(Theme.primaryBorder or Theme.accent) or resolvedColor(Theme.border))
        v.quickBtns[#v.quickBtns + 1] = {
            id = spc.id,
            x = bx,
            y = yPos,
            w = btnW,
            h = LAYOUT.BTN_H,
            label = txt,
            textX = bx + math.floor((btnW - measureOneLine(txt, UIFont.Small)) * 0.5),
            textY = yPos + math.floor((LAYOUT.BTN_H - smallH) * 0.5),
            bg = bg,
            hoverBg = resolvedColor(Theme.hover or bg),
            border = bc,
            textCol = COLORS_STATIC.TEXT_LIGHT,
        }
    end
end

function SquadHudProto:rebuildView()
    local cfg = (self.previewData and self.previewData.config) or SquadService.getConfig()
    local members = (self.previewData and self.previewData.members) or SquadService.getMembers()
    local hudState = (self.previewData and self.previewData.hud) or SquadService.getHudState()
    local collapsed = (self.forceCollapsed ~= nil) and self.forceCollapsed or (hudState.collapsed == true)
    local noticeTxt = (self.previewData and self.previewData.noticeText) or state.noticeText
    local noticeErr = (self.previewData and self.previewData.noticeIsError) or state.noticeIsError

    local smallH = 14
    if type(getTextManager) == "function" then
        local tm = getTextManager()
        if tm and tm.getFontHeight then
            smallH = math.max(12, tm:getFontHeight(UIFont.Small))
        end
    end

    local v = {
        themeRev = Theme and Theme.revision or 0,
        smallH = smallH,
        collapsed = collapsed,
        windowBg = resolvedColor(Theme.window),
        windowBorder = resolvedColor(Theme.border),
        titleFill = resolvedColor(Theme.titleBar or Theme.panel),
        titleAccent = resolvedColor(Theme.titleAccent or Theme.accent),
        titleDivider = resolvedColor(Theme.borderSubtle or Theme.border),
        wellBg = resolvedColor(Theme.well or Theme.panel),
        cardBg = resolvedColor(Theme.card or Theme.panel),
        cardHoverBg = resolvedColor(Theme.hover or Theme.card),
        cardBorder = resolvedColor(Theme.borderSubtle or Theme.border),
        cardHoverBorder = resolvedColor(Theme.hoverBorder or Theme.accent),
        textCol = resolvedColor(Theme.textColor),
        mutedCol = resolvedColor(Theme.muted),
        dangerCol = resolvedColor(Theme.danger),
        warningCol = resolvedColor(Theme.warning or Theme.gold),
        successCol = resolvedColor(Theme.success),
        hpHighCol = resolveModColor("hpHigh", Theme.success),
        hpMidCol = resolveModColor("hpMid", Theme.warning or Theme.gold),
        hpLowCol = resolveModColor("hpLow", Theme.danger),
        overrideCol = resolveModColor("overrideBadge", Theme.gold),
        titleText = fitOneLine(getText("IGUI_ER_Squad_HudTitleFmt", tostring(#members)), 176, UIFont.Small),
        titleY = math.floor((LAYOUT.TITLE_H - smallH) * 0.5),
        titleBtns = {},
        quickBtns = {},
        cards = {},
        scrollMax = 0,
        totalContentCards = #members,
        hasScrollbar = false,
        dragInsertY = nil,
    }

    -- Title-bar action pills: [Wheel], [Collapse], [Close]
    local wheelLbl = fitOneLine(getText("IGUI_ER_Squad_BtnWheel"), 42, UIFont.Small)
    v.titleBtns[1] = {
        id = "btn_wheel",
        x = 194,
        y = 4,
        w = 48,
        h = 18,
        label = wheelLbl,
        textX = 194 + math.floor((48 - measureOneLine(wheelLbl, UIFont.Small)) * 0.5),
        textY = 4 + math.floor((18 - smallH) * 0.5),
        bg = resolvedColor(Theme.control or Theme.panel),
        hoverBg = resolvedColor(Theme.hover or Theme.control),
        border = resolvedColor(Theme.border),
        textCol = resolvedColor(Theme.accent or Theme.textColor),
        icon = nil,
    }
    v.titleBtns[2] = {
        id = "btn_collapse",
        x = 246,
        y = 4,
        w = 20,
        h = 18,
        bg = resolvedColor(Theme.control or Theme.panel),
        hoverBg = resolvedColor(Theme.hover or Theme.control),
        border = resolvedColor(Theme.border),
        textCol = v.textCol,
        icon = collapsed and "chevronDown" or "chevronUp",
    }
    local closeTex = (StatusVisual and StatusVisual.get and StatusVisual.get("close")) or nil
    v.titleBtns[3] = {
        id = "btn_close",
        x = 270,
        y = 4,
        w = 20,
        h = 18,
        bg = resolvedColor(Theme.control or Theme.panel),
        hoverBg = resolvedColor(Theme.danger),
        border = resolvedColor(Theme.border),
        textCol = v.textCol,
        icon = closeTex and "close" or nil,
        label = closeTex and nil or "X",
        textX = 270 + math.floor((20 - measureOneLine("X", UIFont.Small)) * 0.5),
        textY = 4 + math.floor((18 - smallH) * 0.5),
    }

    v.portraitFallbackTex = (StatusVisual and StatusVisual.get and StatusVisual.get("person")) or nil
    v.avatarHeadActive = resolvedColor(Theme.avatarHead, COLORS_STATIC.PORTRAIT_TINT)
    v.avatarBodyActive = resolvedColor(Theme.avatarBody, COLORS_STATIC.PORTRAIT_DIM)

    local keys = SquadService.KEYS
    local fmtLbl = getText(keys.FMT[cfg.formation] or "IGUI_ER_Squad_Fmt_WEDGE")
    local spLbl = getText(keys.SP[cfg.spacing] or "IGUI_ER_Squad_Sp_STANDARD")
    local roeLbl = getText(keys.ROE[cfg.roe] or "IGUI_ER_Squad_Roe_FREE_FIRE")
    local ordLbl = (cfg.orderMode == "HOLD_POSITION")
        and getText("IGUI_ER_Squad_Order_HOLD")
        or getText("IGUI_ER_Squad_Order_FOLLOW")
    local cqbTxt = cfg.cqb and getText("IGUI_ER_Squad_CqbOn") or getText("IGUI_ER_Squad_CqbOff")
    local autoCarTxt = (cfg.autoVehicle ~= false)
        and getText("IGUI_ER_Squad_AutoCarOn")
        or getText("IGUI_ER_Squad_AutoCarOff")
    local vehToggleTxt = cfg.anyInVehicle
        and getText("IGUI_ER_Squad_BtnDismountAll")
        or getText("IGUI_ER_Squad_BtnBoardAll")

    if collapsed then
        local sep = " | "
        local summaryRaw = ordLbl .. sep .. fmtLbl .. " / " .. spLbl .. sep .. roeLbl
        v.summaryText = fitOneLine(summaryRaw, LAYOUT.INNER_W - 4, UIFont.Small)
        v.summaryY = LAYOUT.TITLE_H + math.floor((LAYOUT.SUMMARY_H - smallH) * 0.5)
        v.totalH = LAYOUT.TITLE_H + LAYOUT.SUMMARY_H + 2
        self:setWidth(LAYOUT.WIN_W)
        self:setHeight(v.totalH)
        self.view = v
        return
    end

    -- Expanded Quick Bar: Row 1 (3 buttons, 92px each) + Row 2 (4 buttons, 68px each)
    local yRow1 = LAYOUT.TITLE_H + LAYOUT.QUICK_PAD_TOP
    local yRow2 = yRow1 + LAYOUT.BTN_H + LAYOUT.BTN_GAP
    local w1 = 92
    local w2 = 68

    local row1Specs = {
        { id = "q_order", lbl = ordLbl, active = cfg.orderMode == "HOLD_POSITION", warn = cfg.orderMode == "HOLD_POSITION" },
        { id = "q_formation", lbl = fmtLbl, active = false, warn = false },
        { id = "q_spacing", lbl = spLbl, active = false, warn = false },
    }
    appendQuickBtnRow(v, row1Specs, yRow1, w1, 8, smallH)

    local row2Specs = {
        { id = "q_roe", lbl = roeLbl, active = cfg.roe ~= "FREE_FIRE", warn = cfg.roe == "HOLD_FIRE" },
        { id = "q_cqb", lbl = cqbTxt, active = cfg.cqb == true, warn = false },
        { id = "q_autocar", lbl = autoCarTxt, active = cfg.autoVehicle ~= false, warn = cfg.autoVehicle == false },
        { id = "q_vehicle", lbl = vehToggleTxt, active = cfg.anyInVehicle == true, warn = false },
    }
    appendQuickBtnRow(v, row2Specs, yRow2, w2, 6, smallH)

    local cursorY = yRow2 + LAYOUT.BTN_H + 6
    if noticeTxt and noticeTxt ~= "" then
        v.noticeY = cursorY
        v.noticeText = fitOneLine(noticeTxt, LAYOUT.INNER_W - 12, UIFont.Small)
        v.noticeBg = resolvedColor(Theme.badgeBg or Theme.well)
        v.noticeBorder = noticeErr and v.dangerCol or v.warningCol
        v.noticeTextCol = noticeErr and v.dangerCol or v.warningCol
        cursorY = cursorY + LAYOUT.NOTICE_H + 4
    end

    v.listTop = cursorY
    local n = #members
    if n == 0 then
        v.listH = LAYOUT.EMPTY_H
        v.emptyTitle = fitOneLine(getText("IGUI_ER_Squad_EmptyTitle"), LAYOUT.INNER_W - 16, UIFont.Small)
        v.emptyHint = fitOneLine(getText("IGUI_ER_Squad_EmptyHint"), LAYOUT.INNER_W - 16, UIFont.Small)
        v.totalH = cursorY + v.listH + LAYOUT.FOOTER_PAD
        self:setWidth(LAYOUT.WIN_W)
        self:setHeight(v.totalH)
        self.scrollOffset = 0
        self.view = v
        return
    end

    local visibleCount = math.min(n, LAYOUT.MAX_VISIBLE)
    v.listH = visibleCount * LAYOUT.CARD_H + (visibleCount - 1) * LAYOUT.CARD_GAP
    v.hasScrollbar = n > LAYOUT.MAX_VISIBLE
    v.scrollMax = math.max(0, n - visibleCount)
    self.scrollOffset = math.max(0, math.min(v.scrollMax, self.scrollOffset or 0))

    local cardW = v.hasScrollbar and (LAYOUT.INNER_W - LAYOUT.SCROLLBAR_W - 2) or LAYOUT.INNER_W
    v.cardW = cardW

    local firstIdx = self.scrollOffset + 1
    local lastIdx = math.min(n, firstIdx + visibleCount - 1)
    for idx = firstIdx, lastIdx do
        local m = members[idx]
        local slotIdx = idx - firstIdx
        local cy = cursorY + slotIdx * (LAYOUT.CARD_H + LAYOUT.CARD_GAP)
        local cx = LAYOUT.PAD_X

        local stateKey = keys.ACT[m.activityState] or "IGUI_ER_Squad_Act_WAITING"
        local actLabel = getText(stateKey)
        if m.inVehicle and m.vehicleSeat and m.vehicleSeat >= 0 then
            actLabel = actLabel .. " #" .. tostring(m.vehicleSeat)
        end
        local actBg, actBc, actTc = resolveActivityStyle(m.activityState)
        local actBadge = makeBadgeSpec(fitOneLine(actLabel, 82, UIFont.Small), actBg, actBc, actTc, UIFont.Small)

        local textX = cx + 6 + LAYOUT.PORTRAIT_W + 6
        local nameMaxW = cardW - (LAYOUT.PORTRAIT_W + 24) - actBadge.w
        local shownName = fitOneLine(m.shownName or m.displayName or m.npcId, math.max(48, nameMaxW), UIFont.Small)

        local hpPct = math.max(0, math.min(100, math.floor((m.health or 100) + 0.5)))
        local hpFrac = hpPct / 100.0
        local hpFillCol = v.hpHighCol
        if hpPct < 30 then
            hpFillCol = v.hpLowCol
        elseif hpPct < 65 then
            hpFillCol = v.hpMidCol
        end
        local hpLabel = tostring(hpPct) .. "%"
        local medIcon = nil
        local medCol = nil
        if m.bleeding then
            medIcon = "bleeding"
            medCol = v.dangerCol
        elseif m.injured then
            medIcon = "injured"
            medCol = v.warningCol
        end

        local roleShort = getText(keys.ROLE_SHORT[m.tacticalRole] or "IGUI_ER_Squad_RoleShort_SUPPORT")
        local roleBadge = makeBadgeSpec(roleShort, v.wellBg, v.cardBorder, v.mutedCol, UIFont.Small)

        local roeShort = getText(keys.ROE_SHORT[m.effectiveRoe] or "IGUI_ER_Squad_RoeShort_FREE_FIRE")
        local roeBc = m.roeOverridden and v.overrideCol or v.cardBorder
        local roeTc = m.roeOverridden and v.overrideCol or v.mutedCol
        local roeBadge = makeBadgeSpec(roeShort, v.wellBg, roeBc, roeTc, UIFont.Small)

        local cqbBadge = nil
        if m.effectiveCqb or m.cqbOverridden then
            local cqbShort = m.effectiveCqb and getText("IGUI_ER_Squad_CqbBadgeOn") or getText("IGUI_ER_Squad_CqbBadgeOff")
            local cqbBc = m.cqbOverridden and v.overrideCol or Theme.accent
            local cqbTc = m.cqbOverridden and v.overrideCol or Theme.accent
            cqbBadge = makeBadgeSpec(cqbShort, v.wellBg, cqbBc, cqbTc, UIFont.Small)
        end

        local usedBottomW = roleBadge.w + 4 + roeBadge.w + (cqbBadge and (4 + cqbBadge.w) or 0) + 6
        local availWeaponW = (cardW - (textX - cx) - 6) - usedBottomW
        local weaponStr = ""
        if m.weaponName and m.weaponName ~= "" and availWeaponW >= 24 then
            weaponStr = fitOneLine(m.weaponName, availWeaponW, UIFont.Small)
        elseif m.weaponType and m.weaponType ~= "UNARMED" and availWeaponW >= 24 then
            weaponStr = fitOneLine(getText(keys.WEP[m.weaponType] or "IGUI_ER_Squad_Wep_UNARMED"), availWeaponW, UIFont.Small)
        end

        local hpBarW = 96
        local alertBadge = nil
        if m.pendingDismiss then
            alertBadge = makeBadgeSpec(getText("IGUI_ER_Squad_PendingExit"), v.wellBg, v.warningCol, v.warningCol, UIFont.Small)
        end

        v.cards[#v.cards + 1] = {
            memberIndex = idx,
            npcId = m.npcId,
            x = cx,
            y = cy,
            w = cardW,
            h = LAYOUT.CARD_H,
            phasedIn = m.phasedIn == true,
            portraitX = cx + 6,
            portraitY = cy + 8,
            portraitTex = m.portraitTex,
            portraitTint = m.phasedIn and COLORS_STATIC.PORTRAIT_TINT or COLORS_STATIC.PORTRAIT_DIM,
            avatarHead = m.phasedIn and v.avatarHeadActive or COLORS_STATIC.AVATAR_HEAD_DIM,
            avatarBody = m.phasedIn and v.avatarBodyActive or COLORS_STATIC.AVATAR_BODY_DIM,
            textX = textX,
            shownName = shownName,
            actBadge = actBadge,
            actBadgeX = cx + cardW - 6 - actBadge.w,
            hpBarX = textX,
            hpBarY = cy + 27,
            hpBarW = hpBarW,
            hpFillW = math.max(0, math.floor(hpBarW * hpFrac + 0.5)), -- lint:allow-ratio-math hp bar fill fraction
            hpFillCol = hpFillCol,
            hpLabel = hpLabel,
            hpLabelX = textX + 100,
            medIcon = medIcon,
            medCol = medCol,
            medX = textX + 132,
            alertBadge = alertBadge,
            alertX = cx + cardW - 6 - (alertBadge and alertBadge.w or 0),
            roleBadge = roleBadge,
            roeBadge = roeBadge,
            cqbBadge = cqbBadge,
            weaponStr = weaponStr,
            weaponX = textX + usedBottomW,
        }
    end

    if v.hasScrollbar and n > 0 then
        local trackX = LAYOUT.PAD_X + LAYOUT.INNER_W - LAYOUT.SCROLLBAR_W
        local thumbFrac = visibleCount / n
        local thumbH = math.max(18, math.floor(v.listH * thumbFrac)) -- lint:allow-ratio-math scrollbar thumb height
        local scrollProg = v.scrollMax > 0 and (self.scrollOffset / v.scrollMax) or 0
        local thumbY = cursorY + math.floor((v.listH - thumbH) * scrollProg)
        v.scrollTrackX = trackX
        v.scrollTrackY = cursorY
        v.scrollTrackH = v.listH
        v.scrollThumbY = thumbY
        v.scrollThumbH = thumbH
        v.scrollThumbCol = resolvedColor(Theme.muted)
    end

    v.totalH = cursorY + v.listH + LAYOUT.FOOTER_PAD
    self:setWidth(LAYOUT.WIN_W)
    self:setHeight(v.totalH)
    self.view = v
end

local function drawPillBadge(owner, x, y, h, badge, smallH)
    Chrome.roundedRect(owner, x, y, badge.w, h, badge.bg, badge.border, false, 6)
    Theme.text(owner, badge.label, x + 5, y + math.floor((h - smallH) * 0.5), badge.text, UIFont.Small)
end

function SquadHudProto:prerender()
    local curRev = Theme and Theme.revision or 0
    if not self.view or self.view.themeRev ~= curRev then
        self:rebuildView()
    end
end

function SquadHudProto:render()
    local v = self.view
    if not v then
        return
    end
    local w = LAYOUT.WIN_W
    local h = v.totalH
    local smallH = v.smallH

    Chrome.shadow(self, 0, 0, w, h, 0.42, false)
    Chrome.roundedRect(self, 0, 0, w, h, v.windowBg, v.windowBorder, false, 6)
    Chrome.titleBar(self, 0, 0, w, LAYOUT.TITLE_H, v.titleFill, v.titleAccent, v.titleDivider, 6)
    Theme.text(self, v.titleText, 8, v.titleY, v.textCol, UIFont.Small)

    local tBtns = v.titleBtns
    for i = 1, #tBtns do
        local b = tBtns[i]
        local bg = (self.hoverId == b.id) and b.hoverBg or b.bg
        Chrome.roundedRect(self, b.x, b.y, b.w, b.h, bg, b.border, false, 6)
        if b.icon then
            local ix = b.x + math.floor((b.w - 10) * 0.5)
            local iy = b.y + math.floor((b.h - 10) * 0.5)
            StatusVisual.icon(self, ix, iy, 10, b.icon, b.textCol)
        elseif b.label then
            Theme.text(self, b.label, b.textX, b.textY, b.textCol, UIFont.Small)
        end
    end

    if v.collapsed then
        if v.summaryText then
            Theme.text(self, v.summaryText, 8, v.summaryY, v.mutedCol, UIFont.Small)
        end
        return
    end

    local qBtns = v.quickBtns
    for i = 1, #qBtns do
        local b = qBtns[i]
        local bg = (self.hoverId == b.id) and b.hoverBg or b.bg
        Chrome.roundedRect(self, b.x, b.y, b.w, b.h, bg, b.border, false, 6)
        Theme.text(self, b.label, b.textX, b.textY, b.textCol, UIFont.Small)
    end

    if v.noticeText then
        Chrome.roundedRect(self, LAYOUT.PAD_X, v.noticeY, LAYOUT.INNER_W, LAYOUT.NOTICE_H, v.noticeBg, v.noticeBorder, false, 6)
        Theme.text(self, v.noticeText, LAYOUT.PAD_X + 6, v.noticeY + math.floor((LAYOUT.NOTICE_H - smallH) * 0.5), v.noticeTextCol, UIFont.Small)
    end

    local cards = v.cards
    if #cards == 0 then
        Chrome.roundedRect(self, LAYOUT.PAD_X, v.listTop, LAYOUT.INNER_W, v.listH, v.wellBg, v.cardBorder, false, 6)
        if v.emptyTitle then
            Theme.text(self, v.emptyTitle, LAYOUT.PAD_X + 10, v.listTop + 14, v.textCol, UIFont.Small)
        end
        if v.emptyHint then
            Theme.text(self, v.emptyHint, LAYOUT.PAD_X + 10, v.listTop + 36, v.mutedCol, UIFont.Small)
        end
        return
    end

    for i = 1, #cards do
        local c = cards[i]
        local isHov = (self.hoverCardIdx == c.memberIndex) or (self.dragActive and self.dragSrcIdx == c.memberIndex)
        local bg = isHov and v.cardHoverBg or v.cardBg
        local bc = isHov and v.cardHoverBorder or v.cardBorder
        Chrome.roundedRect(self, c.x, c.y, c.w, c.h, bg, bc, false, 6)

        -- Portrait box (DeadBodyAtlas texture clipped by stencil, or clean humanoid silhouette fallback)
        Chrome.roundedRect(self, c.portraitX, c.portraitY, LAYOUT.PORTRAIT_W, LAYOUT.PORTRAIT_H, v.wellBg, v.cardBorder, false, 6)
        if c.portraitTex and self.drawTextureScaledAspect2 then
            if self.setStencilRect and self.clearStencilRect then
                self:setStencilRect(c.portraitX + 1, c.portraitY + 1, LAYOUT.PORTRAIT_W - 2, LAYOUT.PORTRAIT_H - 2)
                self:drawTextureScaledAspect2(c.portraitTex, c.portraitX - 10, c.portraitY - 4, 60, 92, c.phasedIn and 1.0 or 0.55, 1, 1, 1)
                self:clearStencilRect()
            else
                self:drawTextureScaledAspect2(c.portraitTex, c.portraitX + 1, c.portraitY + 1, LAYOUT.PORTRAIT_W - 2, LAYOUT.PORTRAIT_H - 2, c.phasedIn and 1.0 or 0.55, 1, 1, 1)
            end
        else
            local fTex = v.portraitFallbackTex
            if fTex and self.drawTextureScaled then
                local pt = c.portraitTint
                self:drawTextureScaled(fTex, c.portraitX + 8, c.portraitY + 11, 24, 24, pt.a, pt.r, pt.g, pt.b)
            else
                Theme.rect(self, c.portraitX + 16, c.portraitY + 7, 8, 2, c.avatarHead)
                Theme.rect(self, c.portraitX + 14, c.portraitY + 9, 12, 11, c.avatarHead)
                Theme.rect(self, c.portraitX + 11, c.portraitY + 22, 18, 2, c.avatarBody)
                Theme.rect(self, c.portraitX + 8, c.portraitY + 24, 24, 15, c.avatarBody)
            end
        end

        -- Top line: Name + Activity pill
        Theme.text(self, c.shownName, c.textX, c.y + 6, c.phasedIn and v.textCol or v.mutedCol, UIFont.Small)
        drawPillBadge(self, c.actBadgeX, c.y + 4, 16, c.actBadge, smallH)

        -- Middle line: 3-tier HP bar + value + bleeding/injury/pending badge
        Theme.rect(self, c.hpBarX, c.hpBarY, c.hpBarW, 8, v.wellBg)
        if c.hpFillW > 0 then
            Theme.rect(self, c.hpBarX + 1, c.hpBarY + 1, math.max(1, c.hpFillW - 2), 6, c.hpFillCol)
        end
        Theme.text(self, c.hpLabel, c.hpLabelX, c.y + 24, v.textCol, UIFont.Small)
        if c.medIcon then
            StatusVisual.icon(self, c.medX, c.y + 24, 14, c.medIcon, c.medCol)
        end
        if c.alertBadge then
            drawPillBadge(self, c.alertX, c.y + 23, 15, c.alertBadge, smallH)
        end

        -- Bottom line: Role + personal ROE + personal CQB + weapon
        local bx = c.textX
        local by = c.y + 42
        drawPillBadge(self, bx, by, 15, c.roleBadge, smallH)
        bx = bx + c.roleBadge.w + 4
        drawPillBadge(self, bx, by, 15, c.roeBadge, smallH)
        bx = bx + c.roeBadge.w + 4
        if c.cqbBadge then
            drawPillBadge(self, bx, by, 15, c.cqbBadge, smallH)
        end
        if c.weaponStr and c.weaponStr ~= "" then
            Theme.text(self, c.weaponStr, c.weaponX, by + 1, v.mutedCol, UIFont.Small)
        end
    end

    if v.dragInsertY then
        Theme.rect(self, LAYOUT.PAD_X, v.dragInsertY - 1, v.cardW or LAYOUT.INNER_W, 3, COLORS_STATIC.DRAG_LINE)
    end

    if v.hasScrollbar and v.scrollThumbY then
        Chrome.pill(self, v.scrollTrackX, v.scrollTrackY, LAYOUT.SCROLLBAR_W - 1, v.scrollTrackH, v.wellBg)
        Chrome.pill(self, v.scrollTrackX, v.scrollThumbY, LAYOUT.SCROLLBAR_W - 1, v.scrollThumbH, v.scrollThumbCol)
    end
end

local function pointInBox(px, py, b)
    return px >= b.x and py >= b.y and px <= (b.x + b.w) and py <= (b.y + b.h)
end

function SquadHudProto:hitTest(px, py)
    local v = self.view
    if not v then
        return nil, nil
    end
    for i = 1, #v.titleBtns do
        if pointInBox(px, py, v.titleBtns[i]) then
            return v.titleBtns[i].id, nil
        end
    end
    if not v.collapsed then
        for i = 1, #v.quickBtns do
            if pointInBox(px, py, v.quickBtns[i]) then
                return v.quickBtns[i].id, nil
            end
        end
        for i = 1, #v.cards do
            local c = v.cards[i]
            if pointInBox(px, py, c) then
                return nil, c
            end
        end
    end
    return nil, nil
end

function SquadHudProto:executeQuickAction(btnId)
    if btnId == "btn_wheel" then
        local wheelApi = EmberRoutes and EmberRoutes.api and EmberRoutes.api.squadWheel
        if wheelApi and wheelApi.openClickMode then
            wheelApi.openClickMode()
        end
    elseif btnId == "btn_collapse" then
        local hudState = SquadService.getHudState()
        SquadService.setHudState({ collapsed = not (hudState.collapsed == true) }, "collapse")
    elseif btnId == "btn_close" then
        SquadHUD.close(true)
    elseif btnId == "q_order" then
        SquadService.toggleOrderMode(0)
    elseif btnId == "q_formation" then
        SquadService.cycleFormation()
    elseif btnId == "q_spacing" then
        SquadService.cycleSpacing()
    elseif btnId == "q_roe" then
        SquadService.cycleRoe()
    elseif btnId == "q_cqb" then
        SquadService.toggleCqb()
    elseif btnId == "q_autocar" then
        SquadService.toggleAutoVehicle()
    elseif btnId == "q_vehicle" then
        local res = SquadService.commandVehicleToggle(0)
        if res == "NO_VEHICLE" then
            SquadHUD.showNotice(getText("IGUI_ER_Squad_NoticeNoVehicle"), true)
        elseif res == "FULL" then
            SquadHUD.showNotice(getText("IGUI_ER_Squad_NoticeVehicleFull"), true)
        end
    end
end

function SquadHudProto:openCallsignEditor(member)
    if not ISTextBox or not member then
        return
    end
    local npcId = member.npcId
    local cur = SquadService.getCallsign(npcId) or member.displayName or npcId
    local box = ISTextBox:new(
        0, 0, 280, 140,
        getText("IGUI_ER_Squad_CallsignPromptFmt", tostring(member.displayName or npcId)),
        tostring(cur),
        nil,
        function(_target, btn)
            if btn and btn.internal == "OK" and btn.parent and btn.parent.entry then
                SquadService.setCallsign(npcId, btn.parent.entry:getText())
            end
        end,
        0
    )
    box:initialise()
    box:addToUIManager()
    if box.setAlwaysOnTop then
        box:setAlwaysOnTop(true)
    end
end

function SquadHudProto:openFormationContextMenu()
    if not ISContextMenu then
        return
    end
    local ctx = ISContextMenu.get(0, getMouseX(), getMouseY())
    if not ctx then
        return
    end
    local cfg = SquadService.getConfig()
    local fmts = { "WEDGE", "COLUMN", "LINE", "RING" }
    for i = 1, #fmts do
        local f = fmts[i]
        local opt = ctx:addOption(getText(SquadService.KEYS.FMT[f]), nil, function()
            SquadService.setFormation(f)
        end)
        if opt and cfg.formation == f and ctx.setOptionChecked then
            ctx:setOptionChecked(opt, true)
        end
    end
end

function SquadHudProto:openMemberContextMenu(member)
    if not ISContextMenu or not member then
        return
    end
    local ctx = ISContextMenu.get(0, getMouseX(), getMouseY())
    if not ctx then
        return
    end
    local player = getSpecificPlayer and getSpecificPlayer(0) or nil
    local npcObj = member.phasedIn and NpcRuntime.getLiveObject(member.npcId) or nil

    local invOpt = ctx:addOption(getText("IGUI_ER_Squad_MenuInventory"), nil, function()
        if player and npcObj then
            NpcInventoryPanel.open(player, npcObj)
        end
    end)
    if invOpt and (not player or not npcObj) then
        invOpt.notAvailable = true
    end

    local medOpt = ctx:addOption(getText("IGUI_ER_Squad_MenuMedical"), nil, function()
        if player and npcObj then
            NpcMedicalPanel.open(player, npcObj)
        end
    end)
    if medOpt and (not player or not npcObj) then
        medOpt.notAvailable = true
    end

    ctx:addOption(getText("IGUI_ER_Squad_MenuCallsign"), nil, function()
        self:openCallsignEditor(member)
    end)

    -- Personal ROE submenu
    local roeSub = ISContextMenu:getNew(ctx)
    local roeParent = ctx:addOption(getText("IGUI_ER_Squad_MenuPersonalRoe"), nil, nil)
    ctx:addSubMenu(roeParent, roeSub)
    local roeOpts = {
        { val = nil, label = getText("IGUI_ER_Squad_Inherit"), checked = not member.roeOverridden },
        { val = "FREE_FIRE", label = getText("IGUI_ER_Squad_Roe_FREE_FIRE"), checked = member.roeOverridden and member.effectiveRoe == "FREE_FIRE" },
        { val = "RETURN_FIRE", label = getText("IGUI_ER_Squad_Roe_RETURN_FIRE"), checked = member.roeOverridden and member.effectiveRoe == "RETURN_FIRE" },
        { val = "HOLD_FIRE", label = getText("IGUI_ER_Squad_Roe_HOLD_FIRE"), checked = member.roeOverridden and member.effectiveRoe == "HOLD_FIRE" },
    }
    for i = 1, #roeOpts do
        local ro = roeOpts[i]
        local o = roeSub:addOption(ro.label, nil, function()
            SquadService.setMemberRoe(member.npcId, ro.val)
        end)
        if o and ro.checked and roeSub.setOptionChecked then
            roeSub:setOptionChecked(o, true)
        end
    end

    -- Personal CQB submenu
    local cqbSub = ISContextMenu:getNew(ctx)
    local cqbParent = ctx:addOption(getText("IGUI_ER_Squad_MenuPersonalCqb"), nil, nil)
    ctx:addSubMenu(cqbParent, cqbSub)
    local cqbOpts = {
        { hasVal = false, val = nil, label = getText("IGUI_ER_Squad_Inherit"), checked = not member.cqbOverridden },
        { hasVal = true, val = true, label = getText("IGUI_ER_Squad_CqbOn"), checked = member.cqbOverridden and member.effectiveCqb == true },
        { hasVal = true, val = false, label = getText("IGUI_ER_Squad_CqbOff"), checked = member.cqbOverridden and member.effectiveCqb == false },
    }
    for i = 1, #cqbOpts do
        local co = cqbOpts[i]
        local o = cqbSub:addOption(co.label, nil, function()
            SquadService.setMemberCqb(member.npcId, co.hasVal and co.val or nil)
        end)
        if o and co.checked and cqbSub.setOptionChecked then
            cqbSub:setOptionChecked(o, true)
        end
    end

    if member.inVehicle then
        ctx:addOption(getText("IGUI_ER_Squad_MenuExitVehicle"), nil, function()
            SquadService.commandMemberExitVehicle(member.npcId)
        end)
    end

    ctx:addOption(getText("IGUI_ER_Squad_MenuDismiss"), nil, function()
        local res = SquadService.dismissMember(member.npcId)
        if res == "PENDING_STOP" then
            SquadHUD.showNotice(getText("IGUI_ER_Squad_NoticePendingStop"), false)
        end
    end)
end

function SquadHudProto:onMouseDown(x, y)
    if self.previewData then
        return true
    end
    local btnId, card = self:hitTest(x, y)
    if btnId then
        self.pressedBtnId = btnId
        return true
    end
    if y <= LAYOUT.TITLE_H then
        self.movingWindow = true
        return true
    end
    if card then
        self.dragSrcIdx = card.memberIndex
        self.dragStartY = y
        self.dragActive = false
        self.dragDropSlot = card.memberIndex
        return true
    end
    return true
end

function SquadHudProto:onMouseMove(dx, dy)
    if self.previewData then
        return
    end
    if self.movingWindow then
        self:setX(self:getX() + dx)
        self:setY(self:getY() + dy)
        return
    end
    local mx = self:getMouseX()
    local my = self:getMouseY()
    if self.dragSrcIdx then
        if not self.dragActive and math.abs(my - self.dragStartY) >= 6 then
            self.dragActive = true
        end
        if self.dragActive and self.view and #self.view.cards > 0 then
            local v = self.view
            local rel = (my - v.listTop) / (LAYOUT.CARD_H + LAYOUT.CARD_GAP)
            local firstIdx = self.scrollOffset + 1
            local visible = #v.cards
            local slotOffset = math.max(0, math.min(visible, math.floor(rel + 0.5)))
            self.dragDropSlot = firstIdx + slotOffset
            v.dragInsertY = v.listTop + slotOffset * (LAYOUT.CARD_H + LAYOUT.CARD_GAP) - 2
        end
        return
    end
    local btnId, card = self:hitTest(mx, my)
    self.hoverId = btnId
    self.hoverCardIdx = card and card.memberIndex or nil
end

function SquadHudProto:onMouseMoveOutside(_dx, _dy)
    self.hoverId = nil
    self.hoverCardIdx = nil
    if not self.movingWindow and not self.dragSrcIdx then
        return
    end
end

function SquadHudProto:onMouseUp(x, y)
    if self.previewData then
        return true
    end
    if self.movingWindow then
        self.movingWindow = false
        SquadService.setHudState({ x = self:getX(), y = self:getY() }, false)
        return true
    end
    if self.pressedBtnId then
        local pressed = self.pressedBtnId
        self.pressedBtnId = nil
        local hitBtn = self:hitTest(x, y)
        if hitBtn == pressed then
            self:executeQuickAction(pressed)
        end
        return true
    end
    if self.dragSrcIdx then
        local srcIdx = self.dragSrcIdx
        local wasDrag = self.dragActive
        local dropSlot = self.dragDropSlot
        self.dragSrcIdx = nil
        self.dragActive = false
        self.dragDropSlot = nil
        if self.view then
            self.view.dragInsertY = nil
        end
        if wasDrag and dropSlot then
            local targetIdx = (dropSlot > srcIdx) and (dropSlot - 1) or dropSlot
            if targetIdx ~= srcIdx then
                SquadService.reorderMember(srcIdx, targetIdx)
                return true
            end
        else
            local _, card = self:hitTest(x, y)
            if card then
                local now = nowMs()
                if self.lastClickNpcId == card.npcId and (now - (self.lastClickMs or 0)) <= 360 then
                    self.lastClickNpcId = nil
                    self.lastClickMs = 0
                    local members = SquadService.getMembers()
                    local m = members[card.memberIndex]
                    if m then
                        self:openCallsignEditor(m)
                    end
                else
                    self.lastClickNpcId = card.npcId
                    self.lastClickMs = now
                end
            end
        end
        return true
    end
    return true
end

function SquadHudProto:onMouseUpOutside(_x, _y)
    if self.movingWindow then
        self.movingWindow = false
        SquadService.setHudState({ x = self:getX(), y = self:getY() }, false)
    end
    self.pressedBtnId = nil
    self.dragSrcIdx = nil
    self.dragActive = false
    self.dragDropSlot = nil
    if self.view then
        self.view.dragInsertY = nil
    end
end

function SquadHudProto:onRightMouseUp(x, y)
    if self.previewData then
        return true
    end
    local btnId, card = self:hitTest(x, y)
    if btnId == "q_formation" then
        self:openFormationContextMenu()
        return true
    end
    if card then
        local members = SquadService.getMembers()
        local m = members[card.memberIndex]
        if m then
            self:openMemberContextMenu(m)
        end
        return true
    end
    return true
end

function SquadHudProto:onMouseWheel(del)
    if self.previewData or not self.view or not self.view.hasScrollbar then
        return false
    end
    local nextOff = (self.scrollOffset or 0) + (del or 0)
    nextOff = math.max(0, math.min(self.view.scrollMax or 0, nextOff))
    if nextOff ~= self.scrollOffset then
        self.scrollOffset = nextOff
        self:rebuildView()
    end
    return true
end

local function ensurePanelClass()
    if panelClass then
        return panelClass
    end
    if not ISPanel then
        pcall(require, "ISUI/ISPanel")
    end
    if not ISPanel or type(ISPanel.derive) ~= "function" then
        return nil
    end
    local cls = ISPanel:derive("EmberRoutesSquadHUD")
    for k, fn in pairs(SquadHudProto) do
        cls[k] = fn
    end
    function cls:new(x, y)
        local o = ISPanel:new(x, y, LAYOUT.WIN_W, 120)
        setmetatable(o, self)
        self.__index = self
        o.background = false
        o.moveWithMouse = false
        o.scrollOffset = 0
        o.disposed = false
        return o
    end
    panelClass = cls
    return panelClass
end

local function createPanelInstance(x, y)
    local cls = ensurePanelClass()
    if not cls then
        return nil
    end
    return cls:new(x, y)
end

local function resolveDefaultPos()
    local hs = SquadService.getHudState()
    if type(hs.x) == "number" and type(hs.y) == "number" then
        return hs.x, hs.y
    end
    local core = getCore and getCore() or nil
    local sw = core and core.getScreenWidth and core:getScreenWidth() or 1280
    return math.max(16, sw - LAYOUT.WIN_W - 24), 120
end

function SquadHUD.showNotice(text, isError)
    state.noticeText = text
    state.noticeIsError = isError == true
    state.noticeExpiresMs = nowMs() + 3000
    if state.activePanel and not state.activePanel.disposed then
        state.activePanel:rebuildView()
    end
end

function SquadHUD.isOpen()
    return state.activePanel ~= nil and not state.activePanel.disposed and state.activePanel:getIsVisible()
end

function SquadHUD.getActive()
    return SquadHUD.isOpen() and state.activePanel or nil
end

function SquadHUD.isBlockedByEditor()
    return ZoneEditorPanel and ZoneEditorPanel.getActive and ZoneEditorPanel.getActive() ~= nil
end

function SquadHUD.close(persistHidden)
    local panel = state.activePanel
    state.activePanel = nil
    if state.unsubService then
        local fn = state.unsubService
        state.unsubService = nil
        pcall(fn)
    end
    if panel then
        panel.disposed = true
        if panel.setVisible then
            pcall(panel.setVisible, panel, false)
        end
        if panel.removeFromUIManager then
            pcall(panel.removeFromUIManager, panel)
        end
        if PageLifecycle and PageLifecycle.dispose then
            PageLifecycle.dispose(panel)
        end
    end
    if state.activeScope then
        local sc = state.activeScope
        state.activeScope = nil
        sc:dispose()
    end
    if persistHidden then
        SquadService.setHudState({ visible = false }, false)
    end
end

function SquadHUD.open()
    if state.activePanel and not state.activePanel.disposed then
        state.activePanel:setVisible(true)
        state.activePanel:rebuildView()
        return state.activePanel
    end
    local px, py = resolveDefaultPos()
    local panel = createPanelInstance(px, py)
    if not panel then
        return nil
    end
    panel:initialise()
    panel:instantiate()
    panel:addToUIManager()
    panel:setAlwaysOnTop(false)

    local scope = Kernel.scope("SquadHUD")
    panel.close = function()
        SquadHUD.close(false)
    end
    if PageLifecycle and PageLifecycle.attachExternalScope then
        PageLifecycle.attachExternalScope(panel, scope)
    end

    state.activePanel = panel
    state.activeScope = scope
    SquadService.setHudState({ visible = true }, false)

    state.unsubService = SquadService.subscribe(function()
        if state.activePanel and not state.activePanel.disposed then
            state.activePanel:rebuildView()
        end
    end)

    panel:rebuildView()
    return panel
end

function SquadHUD.toggle()
    if SquadHUD.isOpen() then
        SquadHUD.close(true)
        return false
    end
    SquadHUD.open()
    return true
end

--- 脱机 OpenGL 预览入口（同步支撑展开态与折叠态对照渲染，并在 teardown 时统一销毁）
function SquadHUD.openPreview(opts)
    opts = opts or {}
    local panel = createPanelInstance(opts.x or 40, opts.y or 40)
    if not panel then
        return nil
    end
    panel.previewData = {
        config = opts.config or SquadService.getConfig(),
        members = opts.members or SquadService.getMembers(),
        hud = opts.hud or { collapsed = opts.collapsed == true, visible = true },
        noticeText = opts.noticeText,
        noticeIsError = opts.noticeIsError == true,
    }
    panel.forceCollapsed = opts.collapsed == true
    panel:initialise()
    panel:instantiate()
    panel:addToUIManager()

    local scope = Kernel.scope("SquadHUDPreview")
    panel.close = function(self)
        if not self.disposed then
            self.disposed = true
            self:setVisible(false)
            self:removeFromUIManager()
            if PageLifecycle and PageLifecycle.dispose then
                PageLifecycle.dispose(self)
            end
        end
    end
    if PageLifecycle and PageLifecycle.attachExternalScope then
        PageLifecycle.attachExternalScope(panel, scope)
    end
    state.previewPanels[#state.previewPanels + 1] = panel
    panel:rebuildView()
    return panel
end

local function expireNoticeTick()
    if state.noticeText and nowMs() >= state.noticeExpiresMs then
        state.noticeText = nil
        state.noticeIsError = false
        if state.activePanel and not state.activePanel.disposed then
            state.activePanel:rebuildView()
        end
    end
end

local function disposeAllHudPanels()
    SquadHUD.close(false)
    for i = #state.previewPanels, 1, -1 do
        local p = state.previewPanels[i]
        if p and not p.disposed then
            p:close()
        end
        state.previewPanels[i] = nil
    end
    state.noticeText = nil
end

Kernel.exposeApi("squadHud", SquadHUD)

Kernel.registerModule({
    id = "squad-hud",
    onWorldReady = function(scope)
        local unsub = SquadService.subscribe(function(reason)
            if reason == "addMember" and not SquadHUD.isOpen() then
                SquadHUD.open()
            end
        end)
        scope:defer(unsub)

        local hs = SquadService.getHudState()
        if hs.visible ~= false and #SquadService.getMembers() > 0 then
            SquadHUD.open()
        end

        scope:defer(disposeAllHudPanels)
    end,
    onTeardown = disposeAllHudPanels,
    tick = { stage = "MACRO_WORLD", fn = expireNoticeTick },
    onTickPaused = expireNoticeTick,
})

return SquadHUD
