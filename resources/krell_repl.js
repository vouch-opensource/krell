import TcpSocket from "react-native-tcp-socket";
import Zeroconf from "react-native-zeroconf";
import { getApplicationName, getDeviceId } from 'react-native-device-info';
import {npmDeps} from "./rt.js";

var evaluate = eval;
var libLoadListeners = {};

// =============================================================================
// ZeroConf Service Publication / Discovery

const zeroconf = new Zeroconf();

var bonjourName = function() {
    return "krell.repl" + getApplicationName() + " " + getDeviceId();
};

zeroconf.on("start", () => {
    console.log("Scan started");
});

zeroconf.on("stop", () => {
    console.log("Scan stopped");
});

zeroconf.on("resolved", service => {
    console.log("Service resolved:", JSON.stringify(service));
});

zeroconf.scan("http", "tcp", "local.");

zeroconf.publishService("http", "tcp", "local.", bonjourName(), 5002);

// =============================================================================
// REPL Server

var loadFileSocket = null;

var loadFile = function(socket, path) {
    var req = {
        type: "load-file",
        value: path
    };
    socket.write(JSON.stringify(req));
    socket.write('\0');
};

// NOTE: CLOSURE_LOAD_FILE_SYNC not needed as ClojureScript now transpiles
// offending goog.module files that would need runtime transpiler support
global.CLOSURE_IMPORT_SCRIPT = function(path, optContents) {
    if (optContents) {
        eval(optContents);
        return true;
    } else {
        loadFile(loadFileSocket, path, optContents);
        return true;
    }
};

global.require = function(lib) {
    return npmDeps[lib];
};

var notifyListeners = function(request) {
    var path = request.value,
        xs = libLoadListeners[path] || [];

    xs.forEach(function (x) {
        x();
    });
};

var onSourceLoad = function(path, cb) {
    if(typeof libLoadListeners[path] === "undefined") {
        libLoadListeners[path] = [];
    }
    libLoadListeners[path].push(cb);
};

var server = TcpSocket.createServer(function (socket) {
    var buffer = '',
        ret = null,
        req = null,
        err = null;

    // it doesn't matter which socket we use for loads
    loadFileSocket = socket;

    socket.write("ready");
    socket.write("\0");

    // TODO: I/O forwarding

    socket.on("data", data => {
        if (data[data.length - 1] !== 0) {
            buffer += data;
        } else {
            data = buffer + data;
            buffer = "";

            if (data) {
                data = data.replace(/\0/g, "");

                // ack
                socket.write(
                    JSON.stringify({
                      type: "ack"
                    }) + "\0"
                );

                if (data === ":cljs/quit") {
                    socket.destroy();
                    return;
                } else {
                    try {
                        var obj = JSON.parse(data);
                        req = obj.request;
                        ret = evaluate(obj.form);
                        // output forwarding
                        if(typeof ret == "function") {
                            if(ret.name === "cljs$user$redirect_output") {
                                ret(socket);
                            }
                        }
                    } catch (e) {
                        console.log(e, obj.form);
                        err = e;
                    }
                }
            }

            if (err) {
                socket.write(
                    JSON.stringify({
                        type: "result",
                        status: "exception",
                        value: (typeof cljs != "undefined") ? cljs.repl.error__GT_str(err) : err.toString()
                    }),
                );
            } else {
                if (ret !== undefined && ret !== null) {
                    socket.write(
                        JSON.stringify({
                            type: "result",
                            status: "success",
                            value: ret.toString(),
                        }),
                    );
                } else {
                    socket.write(
                        JSON.stringify({
                            type: "result",
                            status: "success",
                            value: null,
                        }),
                    );
                }

                if (req) {
                    notifyListeners(req);
                }
            }

            req = null;
            ret = null;
            err = null;

            socket.write("\0");
        }
    });

    socket.on("error", error => {
        console.log("An error ocurred with client socket ", error);
    });

    socket.on("close", error => {
        console.log("Closed connection with ", socket.address());
    });
}).listen({port: 5002, host: "0.0.0.0"});

server.on("error", error => {
    console.log("An error ocurred with the server", error);
});

server.on("close", () => {
    console.log("Server closed connection");
});

module.exports = {
    onSourceLoad: onSourceLoad
};
