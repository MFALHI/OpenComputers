os.execute = function(command)
  if not command then
    return type(shell) == "table"
  end
  checkArg(1, command, "string")
  local head, tail = nil, ""
  repeat
    local oldHead = head
    head = command:match("^%S+")
    tail = unicode.sub(command, unicode.len(head) + 1) .. tail
    if head == oldHead then -- say no to infinite recursion, live longer
      command = nil
    else
      command = shell.alias(head)
    end
  until command == nil
  local args = {}
  for part in tail:gmatch("%S+") do
    table.insert(args, part)
  end
  return shell.execute(head, table.unpack(args))
end

function os.exit()
  local result, reason = shell.kill(coroutine.running())
  if result then
    coroutine.yield() -- never returns
  end
  error(reason, 2)
end

os.remove = fs.remove

os.rename = fs.rename

function os.sleep(timeout)
  checkArg(1, timeout, "number", "nil")
  local deadline = os.uptime() + (timeout or 0)
  repeat
    event.pull(deadline - os.uptime())
  until os.uptime() >= deadline
end

function os.tmpname()
  if fs.exists("tmp") then
    for i = 1, 10 do
      local name = "tmp/" .. math.random(1, 0x7FFFFFFF)
      if not fs.exists(name) then
        return name
      end
    end
  end
end