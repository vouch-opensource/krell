import TcpSocket from 'react-native-tcp-socket';
import Zeroconf from 'react-native-zeroconf';

// =============================================================================
// ZeroConf Service Publication / Discovery

var repl = null;
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

var server = TcpSocket.createServer(function(socket) {
  var buffer = '',
      ret    = null,
      err    = null;

  socket.write('ready');
  socket.write('\0');

  socket.on('data', data => {
    if (data[data.length - 1] != '\0') {
      buffer += data;
    } else {
      if (buffer.length > 0) {
        data = buffer + data;
        buffer = '';
      }

      if (data) {
        // not sure how \0's are getting through - David
        data = data.replace(/\0/g, '');

        if (data === ':cljs/quit') {
          server.close();
          socket.unref();
          return;
        } else {
          try {
            dom.run(function() {
              var obj = JSON.parse(data);
              repl = obj.repl;
              ret = eval(obj.form);
            });
          } catch (e) {
            err = e;
          }
        }
      }

      if (err) {
        socket.write(
          JSON.stringify({
            type: 'result',
            repl: repl,
            status: 'exception',
            value: cljs.repl.error__GT_str(err),
          }),
        );
      } else if (ret !== undefined && ret !== null) {
        socket.write(
          JSON.stringify({
            type: 'result',
            repl: repl,
            status: 'success',
            value: ret.toString(),
          }),
        );
      } else {
        socket.write(
          JSON.stringify({
            type: 'result',
            repl: repl,
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
