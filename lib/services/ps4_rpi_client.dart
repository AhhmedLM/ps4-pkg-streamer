import 'dart:async';
import 'dart:convert';
import 'dart:io';

class RpiInstallResult {
  final bool success;
  final int? taskId;
  final String? title;
  final String? error;

  const RpiInstallResult({
    required this.success,
    this.taskId,
    this.title,
    this.error,
  });
}

class RpiTaskProgress {
  final int taskId;
  final int lengthTotal;
  final int transferredTotal;
  final int restSecTotal;
  final String status;
  final int error;

  const RpiTaskProgress({
    required this.taskId,
    required this.lengthTotal,
    required this.transferredTotal,
    required this.restSecTotal,
    required this.status,
    required this.error,
  });

  double get progress {
    if (lengthTotal <= 0) return 0.0;
    return (transferredTotal / lengthTotal).clamp(0.0, 1.0);
  }

  factory RpiTaskProgress.fromJson(int taskId, Map<String, dynamic> json) {
    return RpiTaskProgress(
      taskId: taskId,
      lengthTotal: _toInt(json['length_total']),
      transferredTotal: _toInt(json['transferred_total']),
      restSecTotal: _toInt(json['rest_sec_total']),
      status: (json['status'] as String?) ?? 'unknown',
      error: _toInt(json['error']),
    );
  }

  static int _toInt(dynamic val) {
    if (val is int) return val;
    if (val is num) return val.toInt();
    if (val is String) return int.tryParse(val) ?? 0;
    return 0;
  }
}

/// Client for PS4 GoldHEN Remote Package Installer (RPI) API on port 12800.
class Ps4RpiClient {
  static const int defaultPort = 12800;

  Future<RpiInstallResult> installPkg({
    required String ps4Ip,
    required String packageUrl,
    int port = defaultPort,
  }) async {
    final json = await _postJson(
      ps4Ip: ps4Ip,
      port: port,
      path: '/api/install',
      body: {
        'type': 'direct',
        'packages': [packageUrl],
      },
    );

    final status = json['status']?.toString().toLowerCase();
    if (status == 'success') {
      return RpiInstallResult(
        success: true,
        taskId: RpiTaskProgress._toInt(json['task_id']),
        title: json['title']?.toString(),
      );
    }

    return RpiInstallResult(
      success: false,
      error: json['error']?.toString() ?? json['error_code']?.toString() ?? 'فشل بدء التثبيت على PS4',
    );
  }

  Future<RpiTaskProgress> getTaskProgress({
    required String ps4Ip,
    required int taskId,
    int port = defaultPort,
  }) async {
    final json = await _postJson(
      ps4Ip: ps4Ip,
      port: port,
      path: '/api/get_task_progress',
      body: {'task_id': taskId},
    );
    return RpiTaskProgress.fromJson(taskId, json);
  }

  Future<bool> pauseTask({
    required String ps4Ip,
    required int taskId,
    int port = defaultPort,
  }) async {
    final json = await _postJson(
      ps4Ip: ps4Ip,
      port: port,
      path: '/api/pause_task',
      body: {'task_id': taskId},
    );
    return json['status']?.toString().toLowerCase() == 'success';
  }

  Future<bool> resumeTask({
    required String ps4Ip,
    required int taskId,
    int port = defaultPort,
  }) async {
    final json = await _postJson(
      ps4Ip: ps4Ip,
      port: port,
      path: '/api/resume_task',
      body: {'task_id': taskId},
    );
    return json['status']?.toString().toLowerCase() == 'success';
  }

  Future<bool> stopTask({
    required String ps4Ip,
    required int taskId,
    int port = defaultPort,
  }) async {
    final json = await _postJson(
      ps4Ip: ps4Ip,
      port: port,
      path: '/api/stop_task',
      body: {'task_id': taskId},
    );
    return json['status']?.toString().toLowerCase() == 'success';
  }

  Future<bool> unregisterTask({
    required String ps4Ip,
    required int taskId,
    int port = defaultPort,
  }) async {
    final json = await _postJson(
      ps4Ip: ps4Ip,
      port: port,
      path: '/api/unregister_task',
      body: {'task_id': taskId},
    );
    return json['status']?.toString().toLowerCase() == 'success';
  }

  /// Scans the local /24 subnet for devices listening on PS4 RPI port (12800).
  Future<List<String>> scanForPs4(
    String localIp, {
    int port = defaultPort,
    Duration timeout = const Duration(milliseconds: 350),
  }) async {
    final parts = localIp.split('.');
    if (parts.length != 4) return [];
    final subnet = '${parts[0]}.${parts[1]}.${parts[2]}';
    final found = <String>[];

    // Scan in batches of 32 concurrent sockets to keep it fast and gentle on Wi-Fi.
    for (int start = 1; start <= 254; start += 32) {
      final futures = <Future<void>>[];
      for (int host = start; host < start + 32 && host <= 254; host++) {
        final candidateIp = '$subnet.$host';
        if (candidateIp == localIp) continue;
        futures.add(() async {
          try {
            final socket = await Socket.connect(
              candidateIp,
              port,
              timeout: timeout,
            );
            socket.destroy();
            found.add(candidateIp);
          } catch (_) {
            // Host does not have RPI port open
          }
        }());
      }
      await Future.wait(futures);
    }

    return found;
  }

  Future<Map<String, dynamic>> _postJson({
    required String ps4Ip,
    required int port,
    required String path,
    required Map<String, dynamic> body,
  }) async {
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 8);
    try {
      final uri = Uri.parse('http://$ps4Ip:$port$path');
      final request = await client.postUrl(uri);
      request.headers.set(HttpHeaders.contentTypeHeader, 'application/json');
      final payload = utf8.encode(jsonEncode(body));
      request.contentLength = payload.length;
      request.add(payload);

      final response = await request.close().timeout(const Duration(seconds: 12));
      final raw = await response.transform(utf8.decoder).join();
      if (raw.trim().isEmpty) {
        return {'status': 'fail', 'error': 'Empty response (HTTP ${response.statusCode})'};
      }
      final decoded = jsonDecode(raw);
      if (decoded is Map<String, dynamic>) {
        return decoded;
      }
      return {'status': 'fail', 'error': 'Unexpected JSON response'};
    } finally {
      client.close(force: true);
    }
  }
}
