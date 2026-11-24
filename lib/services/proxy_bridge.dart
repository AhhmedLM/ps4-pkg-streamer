import 'package:flutter/services.dart';

class ProxyStatus {
  final bool running;
  final int port;
  final String localIp;
  final int activeConnections;
  final int totalBytesTransferred;
  final int bytesPerSecond;

  const ProxyStatus({
    required this.running,
    required this.port,
    required this.localIp,
    required this.activeConnections,
    required this.totalBytesTransferred,
    required this.bytesPerSecond,
  });

  factory ProxyStatus.initial() => const ProxyStatus(
        running: false,
        port: 8888,
        localIp: '0.0.0.0',
        activeConnections: 0,
        totalBytesTransferred: 0,
        bytesPerSecond: 0,
      );

  factory ProxyStatus.fromMap(Map<dynamic, dynamic> map) {
    return ProxyStatus(
      running: map['running'] == true,
      port: (map['port'] as num?)?.toInt() ?? 8888,
      localIp: (map['localIp'] as String?) ?? '0.0.0.0',
      activeConnections: (map['activeConnections'] as num?)?.toInt() ?? 0,
      totalBytesTransferred:
          (map['totalBytesTransferred'] as num?)?.toInt() ?? 0,
      bytesPerSecond: (map['bytesPerSecond'] as num?)?.toInt() ?? 0,
    );
  }
}

class RegisteredStream {
  final String id;
  final String proxyUrl;
  final String fileName;
  final String localIp;
  final int port;

  const RegisteredStream({
    required this.id,
    required this.proxyUrl,
    required this.fileName,
    required this.localIp,
    required this.port,
  });

  factory RegisteredStream.fromMap(Map<dynamic, dynamic> map) {
    return RegisteredStream(
      id: map['id'] as String,
      proxyUrl: map['proxyUrl'] as String,
      fileName: map['fileName'] as String,
      localIp: map['localIp'] as String,
      port: (map['port'] as num).toInt(),
    );
  }
}

class LinkProbeResult {
  final bool ok;
  final int statusCode;
  final String contentType;
  final int contentLength;
  final bool supportsRange;
  final bool isValidPkgHeader;
  final String finalUrl;
  final String message;

  const LinkProbeResult({
    required this.ok,
    required this.statusCode,
    required this.contentType,
    required this.contentLength,
    required this.supportsRange,
    required this.isValidPkgHeader,
    required this.finalUrl,
    required this.message,
  });

  factory LinkProbeResult.fromMap(Map<dynamic, dynamic> map) {
    return LinkProbeResult(
      ok: map['ok'] == true,
      statusCode: (map['statusCode'] as num?)?.toInt() ?? 0,
      contentType: (map['contentType'] as String?) ?? '',
      contentLength: (map['contentLength'] as num?)?.toInt() ?? -1,
      supportsRange: map['supportsRange'] == true,
      isValidPkgHeader: map['isValidPkgHeader'] == true,
      finalUrl: (map['finalUrl'] as String?) ?? '',
      message: (map['message'] as String?) ?? '',
    );
  }
}

class ProxyBridge {
  static const _methodChannel =
      MethodChannel('com.ps4pkg.ps4_pkg_streamer/proxy');
  static const _eventChannel =
      EventChannel('com.ps4pkg.ps4_pkg_streamer/stats');
  static const _logChannel =
      EventChannel('com.ps4pkg.ps4_pkg_streamer/logs');

  Stream<ProxyStatus> get statsStream => _eventChannel
      .receiveBroadcastStream()
      .map((event) => ProxyStatus.fromMap(event as Map<dynamic, dynamic>));

  Stream<String> get logsStream => _logChannel
      .receiveBroadcastStream()
      .map((event) => event.toString());

  Future<ProxyStatus> startService({int port = 8888}) async {
    final res = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>(
      'startService',
      {'port': port},
    );
    return res != null ? ProxyStatus.fromMap(res) : ProxyStatus.initial();
  }

  Future<void> stopService() async {
    await _methodChannel.invokeMethod('stopService');
  }

  Future<ProxyStatus> getStatus() async {
    final res = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>(
      'getStatus',
    );
    return res != null ? ProxyStatus.fromMap(res) : ProxyStatus.initial();
  }

  Future<LinkProbeResult> probeUrl({
    required String url,
    Map<String, String> headers = const {},
  }) async {
    final res = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>(
      'probeUrl',
      {
        'url': url,
        'headers': headers,
      },
    );
    if (res == null) {
      throw Exception('فشل فحص الرابط');
    }
    return LinkProbeResult.fromMap(res);
  }

  Future<RegisteredStream> registerUrl({
    required String url,
    String? fileName,
    Map<String, String> headers = const {},
  }) async {
    final res = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>(
      'registerUrl',
      {
        'url': url,
        'fileName': fileName,
        'headers': headers,
      },
    );
    if (res == null) {
      throw Exception('فشل تسجيل رابط البث في الخادم المحلي');
    }
    return RegisteredStream.fromMap(res);
  }

  Future<void> unregisterUrl(String id) async {
    await _methodChannel.invokeMethod('unregisterUrl', {'id': id});
  }

  Future<void> resetStats() async {
    await _methodChannel.invokeMethod('resetStats');
  }

  Future<GoldHenInstallResult> installViaGoldHen({
    required String ps4Ip,
    required String url,
    Map<String, String> headers = const {},
  }) async {
    final res = await _methodChannel.invokeMethod<Map<dynamic, dynamic>>(
      'installViaGoldHen',
      {
        'ps4Ip': ps4Ip,
        'url': url,
        'headers': headers,
      },
    );
    if (res == null) {
      return const GoldHenInstallResult(
        success: false,
        title: '',
        contentId: '',
        packageSize: 0,
        streamId: '',
        error: 'فشل الاتصال بخدمة GoldHEN المحلية',
      );
    }
    return GoldHenInstallResult.fromMap(res);
  }
}

class GoldHenInstallResult {
  final bool success;
  final String title;
  final String contentId;
  final int packageSize;
  final String streamId;
  final String? error;

  const GoldHenInstallResult({
    required this.success,
    required this.title,
    required this.contentId,
    required this.packageSize,
    required this.streamId,
    this.error,
  });

  factory GoldHenInstallResult.fromMap(Map<dynamic, dynamic> map) {
    final err = map['error']?.toString();
    return GoldHenInstallResult(
      success: map['success'] == true,
      title: (map['title'] as String?) ?? '',
      contentId: (map['contentId'] as String?) ?? '',
      packageSize: (map['packageSize'] as num?)?.toInt() ?? 0,
      streamId: (map['streamId'] as String?) ?? '',
      error: (err == null || err.isEmpty) ? null : err,
    );
  }
}
