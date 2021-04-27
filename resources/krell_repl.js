import { Platform } from "react-native";
import TcpSocket from "react-native-tcp-socket";
import DeviceInfo from "react-native-device-info";
import { krellPortMap } from '../app.json';
import {
    bootstrapRepl,
    evaluate,
    loadFile,
    onSourceLoad
} from "./closure_bootstrap.js";

var CONNECTED = false;
var RECONNECT_INTERVAL = 3000;

var SERVER_IP = "$KRELL_SERVER_IP";
var SERVER_PORT = krellPortMap ? krellPortMap[DeviceInfo.getDeviceId()] : $KRELL_SERVER_PORT;

const KRELL_VERBOSE = $KRELL_VERBOSE;

var reloadListeners = [];

console.log("Krell sez howdy, Device ID:", DeviceInfo.getDeviceId());

// =============================================================================
// REPL Server

const exists_ = (obj, xs) => {
    if(typeof xs == "string") {
        xs = xs.split(".");
    }
    if(xs.length >= 1) {
        let key = xs[0],
            hasKey = obj.hasOwnProperty(key);
        if (xs.length === 1) {
            return hasKey;
        } else {
            if(hasKey) {
                return exists_(obj[key], xs.slice(1));
            }
        }
        return false;
    } else {
        return false;
    }
};

const notifyReloadListeners = () => {
    reloadListeners.forEach(function(x) {
        x();
    });
};

const onKrellReload = (cb) => {
    reloadListeners.push(cb);
};

const errString = (err) => {
    if(typeof cljs !== "undefined") {
        if(typeof cljs.repl !== "undefined") {
            cljs.repl.error__GT_str(err)
        } else {
            return err.toString();
        }
    } else {
        return err.toString();
    }
};

const handleMessage = (socket, data) => {
    var err = null,
        ret = null,
        msg = null;

    data = data.replace(/\0/g, "");

    try {
        msg = JSON.parse(data);
        if(msg.form) {
            ret = evaluate(msg.form);
        }
    } catch (e) {
        if(msg && msg.form) {
            console.error("Could not evaluate form:", msg.form, e);
        } else {
            console.error("Invalid message:", msg, e);
        }
        err = e;
    }

    if (err) {
        socket.write(
            JSON.stringify({
                type: "result",
                status: "exception",
                value: errString(err)
            })+"\0"
        );
    } else {
        if (ret !== undefined && ret !== null) {
            socket.write(
                JSON.stringify({
                    type: "result",
                    status: "success",
                    value: ret.toString(),
                })+"\0"
            );
        } else {
            socket.write(
                JSON.stringify({
                    type: "result",
                    status: "success",
                    value: null,
                })+"\0"
            );
        }
    }
};

global.KRELL_RELOAD = async function(nses) {
    for(let ns of nses) {
        let path = goog.debugLoader_.getPathFromDeps_(ns);
        await loadFile(path);
    }
    notifyReloadListeners();
}

const initSocket = (socket) => {
    var buffer = "";

    socket.on("data", data => {
        if (data[data.length - 1] !== 0) {
            buffer += data;
        } else {
            data = buffer + data;
            buffer = "";
            handleMessage(socket, data);
        }
    });

    socket.on("error", error => {
        socket.destroy();
        //console.log("An error occurred with client socket:", error);
    });

    socket.on("close", error => {
        socket.destroy();
        if (CONNECTED) {
            console.log("Closed connection with", socket.address());
        }
        CONNECTED = false;
        setTimeout(tryConnection, RECONNECT_INTERVAL);
    });
};

// Loop to connect from client to server
const tryConnection = () => {
    if(!CONNECTED) {
        var socket = TcpSocket.createConnection({
            host: SERVER_IP,
            port: SERVER_PORT,
            tls: false,
        }, function(address) {
            console.log("Connected to Krell REPL Server");
            CONNECTED = true;
            // once cljs.core loaded, can monkey-patch Closure and setup printing
            if(exists_(global, "cljs.core")) {
                bootstrapRepl(socket);
            } else {
                onSourceLoad(goog.debugLoader_.getPathFromDeps_("cljs.core"), () => {
                    bootstrapRepl(socket);
                });
            }
        });
        initSocket(socket);
    } else {
        setTimeout(tryConnection, RECONNECT_INTERVAL);
    }
};

if(!process.env["KRELL_NO_REPL"]) {
    tryConnection();
}

module.exports = {
    evaluate: evaluate,
    onKrellReload: onKrellReload,
    onSourceLoad: onSourceLoad
};
