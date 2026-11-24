import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:ps4_pkg_streamer/services/ps4_rpi_client.dart';

void main() {
  group('Ps4RpiClient', () {
    late HttpServer mockPs4Server;
    late Ps4RpiClient client;

    setUp(() async {
      HttpOverrides.global = null;
      client = Ps4RpiClient();
      mockPs4Server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      mockPs4Server.listen((HttpRequest req) async {
        final bodyStr = await utf8.decoder.bind(req).join();
        final body = jsonDecode(bodyStr) as Map<String, dynamic>;

        req.response.headers.contentType = ContentType.json;
        switch (req.uri.path) {
          case '/api/install':
            final packages = body['packages'] as List<dynamic>;
            req.response.write(jsonEncode({
              'status': 'success',
              'task_id': 42,
              'title': 'Test Game (${packages.first})',
            }));
            break;
          case '/api/get_task_progress':
            req.response.write(jsonEncode({
              'status': 'success',
              'length_total': 1000,
              'transferred_total': 500,
              'rest_sec_total': 10,
              'error': 0,
            }));
            break;
          case '/api/pause_task':
          case '/api/resume_task':
          case '/api/stop_task':
          case '/api/unregister_task':
            req.response.write(jsonEncode({'status': 'success'}));
            break;
          default:
            req.response.statusCode = 404;
            req.response.write(
                jsonEncode({'status': 'fail', 'error': 'not found'}));
        }
        await req.response.close();
      });
    });

    tearDown(() async {
      await mockPs4Server.close(force: true);
    });

    test('installs PKG, polls progress, pauses and resumes task', () async {
      final res = await client.installPkg(
        ps4Ip: '127.0.0.1',
        port: mockPs4Server.port,
        packageUrl: 'http://192.168.1.50:8888/pkg/abc123.pkg',
      );
      expect(res.success, isTrue);
      expect(res.taskId, 42);

      final prog = await client.getTaskProgress(
        ps4Ip: '127.0.0.1',
        port: mockPs4Server.port,
        taskId: 42,
      );
      expect(prog.lengthTotal, 1000);
      expect(prog.transferredTotal, 500);
      expect(prog.progress, 0.5);

      expect(
        await client.pauseTask(
          ps4Ip: '127.0.0.1',
          port: mockPs4Server.port,
          taskId: 42,
        ),
        isTrue,
      );
      expect(
        await client.resumeTask(
          ps4Ip: '127.0.0.1',
          port: mockPs4Server.port,
          taskId: 42,
        ),
        isTrue,
      );
    });
  });
}
