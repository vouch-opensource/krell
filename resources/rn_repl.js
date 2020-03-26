import TcpSocket from 'react-native-tcp-socket';
import Zeroconf from 'react-native-zeroconf';

// Boostrap for Google Closure Library
global.goog = {};
var evaluate = eval;

// =============================================================================
// ZeroConf Service Publication / Discovery

const zeroconf = new Zeroconf();

zeroconf.on('start', () => {
  console.log('Scan started');
});

zeroconf.on('stop', () => {
  console.log('Scan stopped');
});

zeroconf.on('resolved', service => {
  console.log('Service resolved:', JSON.stringify(service));
});

zeroconf.scan('http', 'tcp', 'local.');

zeroconf.publishService('http', 'tcp', 'local.', 'rn.repl', 5002);

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

global.CLOSURE_IMPORT_SCRIPT = function(path, optContents) {
  if (optContents) {
    eval(optContents);
    return true;
  } else {
    loadFile(loadFileSocket, path, optContents);
    return true;
  }
};

var server = TcpSocket.createServer(function(socket) {
  var buffer = '',
      ret    = null,
      err    = null;

  // it doesn't matter which socket we use for loads
  loadFileSocket = socket;

  socket.write('ready');
  socket.write('\0');

  // TODO: I/O forwarding

  socket.on('data', data => {
    if (data[data.length - 1] != 0) {
      buffer += data;
    } else {
      data = buffer + data;
      buffer = '';

      if (data) {
        data = data.replace(/\0/g, '');

        if (data === ':cljs/quit') {
          server.close();
          return;
        } else {
          try {
            var obj = JSON.parse(data);
            ret = evaluate(obj.form);
          } catch (e) {
            console.log(e, obj.form);
            err = e;
          }
        }
      }

      if (err) {
        socket.write(
          JSON.stringify({
            type: 'result',
            status: 'exception',
            value: (typeof cljs != 'undefined') ? cljs.repl.error__GT_str(err) : err.toString()
          }),
        );
      } else if (ret !== undefined && ret !== null) {
        socket.write(
          JSON.stringify({
            type: 'result',
            status: 'success',
            value: ret.toString(),
          }),
        );
      } else {
        socket.write(
          JSON.stringify({
            type: 'result',
            status: 'success',
            value: null,
          }),
        );
      }

      ret = null;
      err = null;

      socket.write('\0');
    }
  });

  socket.on('error', error => {
    console.log('An error ocurred with client socket ', error);
  });

  socket.on('close', error => {
    console.log('Closed connection with ', socket.address());
  });
}).listen({port: 5002, host: '0.0.0.0'});

server.on('error', error => {
  console.log('An error ocurred with the server', error);
});

server.on('close', () => {
  console.log('Server closed connection');
});
