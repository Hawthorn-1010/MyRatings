if (redis.call('GET', KEY[1]) == ARGV[1]) then
    return redis.call('DEL', KEYS[1])
end
return 0