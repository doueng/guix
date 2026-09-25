
function uuid -d 'create lowercase uuid'
    uuidgen | awk '{print tolower($0)}'
end
