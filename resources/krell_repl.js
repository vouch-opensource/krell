import { Platform } from "react-native";
import TcpSocket from "react-native-tcp-socket";
import {
    bootstrapRepl,
    evaluate,
    loadFile,
    onSourceLoad
} from "./closure_bootstrap.js";

var CONNECTED = false;
var RECONNECT_INTERVAL = 3000;

var SERVER_IP = "$KRELL_SERVER_IP";
var SERVER_PORT = $KRELL_SERVER_PORT;

const IS_ANDROID = Platform.OS === "android";
const KRELL_VERBOSE = $KRELL_VERBOSE;

var reloadListeners = [];

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
    var req = null,
        err = null,
        ret = null;

    data = data.replace(/\0/g, "");

    try {
        let msg = JSON.parse(data);
        req = msg.request;
        if(msg.form) {
            ret = evaluate(msg.form);
        }
    } catch (e) {
        console.error(e);
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

        // TODO: need to move this to new HTTP source file loader
        if (req) {
            notifyListeners(req);
        }
    }
};

global.KRELL_RELOAD = async function(nses) {
    for(let ns of nses) {
        await loadFile(ns);
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
        console.log("An error occurred with client socket:", error);
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
        });
        initSocket(socket);
    } else {
        setTimeout(tryConnection, RECONNECT_INTERVAL);
    }
};

tryConnection();

module.exports = {
    evaluate: evaluate,
    onKrellReload: onKrellReload,
    onSourceLoad: onSourceLoad
};
