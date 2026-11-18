local Geometry = {
    MAX_SIDE = 128,
    MAX_AREAS = 128,
}

local function isCoord(n)
    return type(n) == "number" and n == n and math.abs(n) <= 10000000 and n == math.floor(n)
end

function Geometry.rectangle(x1, y1, x2, y2, z)
    if not isCoord(x1) or not isCoord(y1) or not isCoord(x2) or not isCoord(y2) then
        return nil, "INVALID_BOUNDS"
    end
    if z ~= nil and not isCoord(z) then
        return nil, "INVALID_FLOOR"
    end
    local r = {
        x = math.min(x1, x2),
        y = math.min(y1, y2),
        w = math.abs(x2 - x1) + 1,
        h = math.abs(y2 - y1) + 1,
        z = z,
    }
    if r.w > Geometry.MAX_SIDE or r.h > Geometry.MAX_SIDE then
        return nil, "AREA_LIMIT"
    end
    return r
end

function Geometry.valid(r)
    return type(r) == "table"
        and isCoord(r.x) and isCoord(r.y) and isCoord(r.w) and isCoord(r.h)
        and r.w >= 1 and r.h >= 1
        and r.w <= Geometry.MAX_SIDE and r.h <= Geometry.MAX_SIDE
        and (r.z == nil or isCoord(r.z))
end

function Geometry.contains(r, x, y)
    return x >= r.x and y >= r.y and x < r.x + r.w and y < r.y + r.h
end

function Geometry.insideAreas(areas, x, y)
    if type(areas) ~= "table" then return false end
    for i = 1, #areas do
        local r = areas[i]
        if Geometry.valid(r) and Geometry.contains(r, x, y) then
            return true
        end
    end
    return false
end

function Geometry.rectFullyInsideAreas(rect, areas)
    if not Geometry.valid(rect) or type(areas) ~= "table" or #areas == 0 then
        return false
    end
    local remain = { Geometry.cloneArea(rect) }
    for i = 1, #areas do
        local nextRemain = {}
        local area = areas[i]
        for j = 1, #remain do
            local parts = Geometry.subtract(remain[j], area)
            for k = 1, #parts do
                nextRemain[#nextRemain + 1] = parts[k]
            end
        end
        remain = nextRemain
        if #remain == 0 then
            return true
        end
    end
    return #remain == 0
end

function Geometry.overlap(a, b, gap)
    gap = gap or 0
    return a.x < b.x + b.w + gap
        and a.x + a.w + gap > b.x
        and a.y < b.y + b.h + gap
        and a.y + a.h + gap > b.y
end

function Geometry.cloneArea(r)
    if not Geometry.valid(r) then return nil end
    return { x = r.x, y = r.y, w = r.w, h = r.h, z = r.z }
end

function Geometry.cloneAreas(areas)
    local copy = {}
    if areas then
        for i = 1, #areas do
            local area = Geometry.cloneArea(areas[i])
            if area then copy[#copy + 1] = area end
        end
    end
    return copy
end

function Geometry.totalTiles(areas)
    if not areas then return 0 end
    local count = 0
    for i = 1, #areas do
        local r = areas[i]
        if Geometry.valid(r) then
            count = count + (r.w * r.h)
        end
    end
    return count
end

function Geometry.subtract(a, b)
    if not Geometry.valid(a) then return {} end
    if not Geometry.valid(b) or (a.z ~= nil and b.z ~= nil and a.z ~= b.z) then
        return { Geometry.cloneArea(a) }
    end
    local ax1, ay1, ax2, ay2 = a.x, a.y, a.x + a.w, a.y + a.h
    local bx1, by1, bx2, by2 = b.x, b.y, b.x + b.w, b.y + b.h
    local ix1, iy1 = math.max(ax1, bx1), math.max(ay1, by1)
    local ix2, iy2 = math.min(ax2, bx2), math.min(ay2, by2)
    if ix1 >= ix2 or iy1 >= iy2 then
        return { Geometry.cloneArea(a) }
    end
    local res = {}
    if iy1 > ay1 then res[#res + 1] = { x = ax1, y = ay1, w = ax2 - ax1, h = iy1 - ay1, z = a.z } end
    if ay2 > iy2 then res[#res + 1] = { x = ax1, y = iy2, w = ax2 - ax1, h = ay2 - iy2, z = a.z } end
    if ix1 > ax1 then res[#res + 1] = { x = ax1, y = iy1, w = ix1 - ax1, h = iy2 - iy1, z = a.z } end
    if ax2 > ix2 then res[#res + 1] = { x = ix2, y = iy1, w = ax2 - ix2, h = iy2 - iy1, z = a.z } end
    return res
end

function Geometry.compact(areas)
    if not areas or #areas <= 1 then
        return Geometry.cloneAreas(areas or {})
    end
    local list = Geometry.cloneAreas(areas)
    local merged = true
    while merged do
        merged = false
        local len = #list
        for i = 1, len - 1 do
            local a = list[i]
            if a then
                for j = i + 1, len do
                    local b = list[j]
                    if b and a.z == b.z then
                        if a.x == b.x and a.w == b.w and (a.y + a.h == b.y or b.y + b.h == a.y) and (a.h + b.h <= Geometry.MAX_SIDE) then
                            list[i] = { x = a.x, y = math.min(a.y, b.y), w = a.w, h = a.h + b.h, z = a.z }
                            table.remove(list, j)
                            merged = true
                            break
                        elseif a.y == b.y and a.h == b.h and (a.x + a.w == b.x or b.x + b.w == a.x) and (a.w + b.w <= Geometry.MAX_SIDE) then
                            list[i] = { x = math.min(a.x, b.x), y = a.y, w = a.w + b.w, h = a.h, z = a.z }
                            table.remove(list, j)
                            merged = true
                            break
                        end
                    end
                end
                if merged then break end
            end
        end
    end
    return list
end

function Geometry.subtractFromList(areas, cutRect)
    if not areas or #areas == 0 then return {} end
    if not Geometry.valid(cutRect) then return Geometry.cloneAreas(areas) end
    local res = {}
    for i = 1, #areas do
        local parts = Geometry.subtract(areas[i], cutRect)
        for j = 1, #parts do
            res[#res + 1] = parts[j]
        end
    end
    return Geometry.compact(res)
end

function Geometry.addToList(areas, newRect)
    if not Geometry.valid(newRect) then return Geometry.cloneAreas(areas or {}) end
    local res = Geometry.cloneAreas(areas or {})
    local additions = { Geometry.cloneArea(newRect) }
    for i = 1, #res do
        local existing = res[i]
        local nextAdd = {}
        for j = 1, #additions do
            local parts = Geometry.subtract(additions[j], existing)
            for k = 1, #parts do
                nextAdd[#nextAdd + 1] = parts[k]
            end
        end
        additions = nextAdd
        if #additions == 0 then break end
    end
    for i = 1, #additions do
        res[#res + 1] = additions[i]
    end
    return Geometry.compact(res)
end

--- 将任意矩形列表规约为严格互不重叠且相邻合并的正交子矩形集合。
function Geometry.normalizeDisjoint(areas)
    if type(areas) ~= "table" or #areas == 0 then
        return {}
    end
    local out = {}
    for i = 1, #areas do
        local r = areas[i]
        if Geometry.valid(r) then
            out = Geometry.addToList(out, r)
        end
    end
    return out
end

local function mergeSpans(raw)
    if not raw or #raw == 0 then return {} end
    local sorted = {}
    for i = 1, #raw do
        local s = raw[i]
        if s[2] > s[1] then
            sorted[#sorted + 1] = { s[1], s[2] }
        end
    end
    if #sorted == 0 then return {} end
    table.sort(sorted, function(a, b)
        if a[1] ~= b[1] then return a[1] < b[1] end
        return a[2] < b[2]
    end)
    local merged = {}
    local cur = sorted[1]
    for i = 2, #sorted do
        local nxt = sorted[i]
        if nxt[1] <= cur[2] then
            if nxt[2] > cur[2] then cur[2] = nxt[2] end
        else
            merged[#merged + 1] = cur
            cur = nxt
        end
    end
    merged[#merged + 1] = cur
    return merged
end

local function subtractSpans(a, b)
    if #a == 0 then return {} end
    if #b == 0 then return a end
    local out = {}
    local bi, bn = 1, #b
    for ai = 1, #a do
        local s, e = a[ai][1], a[ai][2]
        while bi <= bn and b[bi][2] <= s do
            bi = bi + 1
        end
        local cur = s
        local k = bi
        while k <= bn and b[k][1] < e do
            local bs, be = b[k][1], b[k][2]
            if bs > cur then
                out[#out + 1] = { cur, math.min(e, bs) }
            end
            if be > cur then cur = be end
            if cur >= e then break end
            k = k + 1
        end
        if cur < e then
            out[#out + 1] = { cur, e }
        end
    end
    return out
end

local function xorSpans(a, b)
    local ma, mb = mergeSpans(a), mergeSpans(b)
    if #ma == 0 then return mb end
    if #mb == 0 then return ma end
    local diff = subtractSpans(ma, mb)
    local rev = subtractSpans(mb, ma)
    for i = 1, #rev do
        diff[#diff + 1] = rev[i]
    end
    return mergeSpans(diff)
end

--- 提取不规则领地的纯外轮廓线段（含内院洞口，剔除全部内部拼接缝），返回 {{x1, y1, x2, y2}, ...}。
function Geometry.extractOutlineSegments(areas)
    local rects = Geometry.normalizeDisjoint(areas)
    if #rects == 0 then return {} end

    local northByY, southByY, ySet = {}, {}, {}
    local westByX, eastByX, xSet = {}, {}, {}
    local ys, xs = {}, {}

    for i = 1, #rects do
        local r = rects[i]
        local x1, y1, x2, y2 = r.x, r.y, r.x + r.w, r.y + r.h
        if not ySet[y1] then ySet[y1] = true; ys[#ys + 1] = y1 end
        if not ySet[y2] then ySet[y2] = true; ys[#ys + 1] = y2 end
        if not xSet[x1] then xSet[x1] = true; xs[#xs + 1] = x1 end
        if not xSet[x2] then xSet[x2] = true; xs[#xs + 1] = x2 end

        local ny = northByY[y1]; if not ny then ny = {}; northByY[y1] = ny end
        ny[#ny + 1] = { x1, x2 }
        local sy = southByY[y2]; if not sy then sy = {}; southByY[y2] = sy end
        sy[#sy + 1] = { x1, x2 }

        local wx = westByX[x1]; if not wx then wx = {}; westByX[x1] = wx end
        wx[#wx + 1] = { y1, y2 }
        local ex = eastByX[x2]; if not ex then ex = {}; eastByX[x2] = ex end
        ex[#ex + 1] = { y1, y2 }
    end

    table.sort(ys)
    table.sort(xs)

    local segs = {}
    for i = 1, #ys do
        local y = ys[i]
        local spans = xorSpans(northByY[y], southByY[y])
        for j = 1, #spans do
            segs[#segs + 1] = { spans[j][1], y, spans[j][2], y }
        end
    end
    for i = 1, #xs do
        local x = xs[i]
        local spans = xorSpans(westByX[x], eastByX[x])
        for j = 1, #spans do
            segs[#segs + 1] = { x, spans[j][1], x, spans[j][2] }
        end
    end
    return segs
end

return Geometry
