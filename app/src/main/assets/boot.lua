local g = getgenv and getgenv() or _G
g.identifyexecutor = function() return "ShiroRun" end
g.getexecutorname = g.identifyexecutor
g.getcustomasset = function(p) return "rbxasset://" .. p end
g.keypress = function(k, d)
    if not keypress or not VirtualKeyCode then return end
    local vk = VirtualKeyCode[k]
    if not vk then return end
    keypress(vk)
    task.wait(d or 0.1)
    if keyrelease then keyrelease(vk) end
end
