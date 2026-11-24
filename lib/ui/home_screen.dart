import 'dart:async';
import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:material_ui/material_ui.dart';

import '../services/proxy_bridge.dart';
import '../services/ps4_rpi_client.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _proxyBridge = ProxyBridge();
  final _rpiClient = Ps4RpiClient();

  final _ps4IpController = TextEditingController(text: '192.168.1.100');
  final _pkgUrlController = TextEditingController();
  final _usernameController = TextEditingController();
  final _passwordController = TextEditingController();
  final _cookieController = TextEditingController();
  final _refererController = TextEditingController();
  final _userAgentController = TextEditingController();

  ProxyStatus _proxyStatus = ProxyStatus.initial();
  StreamSubscription<ProxyStatus>? _statsSub;
  StreamSubscription<String>? _logsSub;
  Timer? _progressTimer;

  bool _useGoldHenBinLoader = true;
  bool _isScanning = false;
  bool _isInstalling = false;
  bool _isProbing = false;
  bool _showAdvancedHeaders = false;
  List<String> _discoveredPs4s = [];
  LinkProbeResult? _probeResult;

  int? _activeTaskId;
  String? _activeStreamId;
  String? _activeTitle;
  int _goldHenTotalSize = 0;
  RpiTaskProgress? _taskProgress;

  final List<String> _logs = [];

  @override
  void initState() {
    super.initState();
    _initProxyState();
  }

  Future<void> _initProxyState() async {
    _statsSub = _proxyBridge.statsStream.listen(
      (status) {
        if (mounted) {
          setState(() => _proxyStatus = status);
        }
      },
      onError: (err) => _addLog('خطأ في قناة الإحصائيات: $err'),
    );

    _logsSub = _proxyBridge.logsStream.listen(
      (msg) => _addLog('[Proxy] $msg'),
      onError: (_) {},
    );

    try {
      final status = await _proxyBridge.getStatus();
      if (mounted) {
        setState(() => _proxyStatus = status);
      }
    } catch (e) {
      _addLog('تعذر جلب حالة الخادم: $e');
    }
  }

  @override
  void dispose() {
    _statsSub?.cancel();
    _logsSub?.cancel();
    _progressTimer?.cancel();
    _ps4IpController.dispose();
    _pkgUrlController.dispose();
    _usernameController.dispose();
    _passwordController.dispose();
    _cookieController.dispose();
    _refererController.dispose();
    _userAgentController.dispose();
    super.dispose();
  }

  void _addLog(String message) {
    final now = DateTime.now();
    final ts =
        '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}:${now.second.toString().padLeft(2, '0')}';
    if (!mounted) return;
    setState(() {
      _logs.insert(0, '[$ts] $message');
      if (_logs.length > 60) _logs.removeLast();
    });
  }

  Map<String, String> _buildCustomHeaders() {
    final headers = <String, String>{};
    final user = _usernameController.text.trim();
    final pass = _passwordController.text;
    if (user.isNotEmpty || pass.isNotEmpty) {
      final token = base64Encode(utf8.encode('$user:$pass'));
      headers['Authorization'] = 'Basic $token';
    }
    if (_cookieController.text.trim().isNotEmpty) {
      headers['Cookie'] = _cookieController.text.trim();
    }
    if (_refererController.text.trim().isNotEmpty) {
      headers['Referer'] = _refererController.text.trim();
    }
    if (_userAgentController.text.trim().isNotEmpty) {
      headers['User-Agent'] = _userAgentController.text.trim();
    }
    return headers;
  }

  Future<void> _toggleService() async {
    try {
      if (_proxyStatus.running) {
        await _proxyBridge.stopService();
        _addLog('تم إيقاف خادم البث في الخلفية.');
      } else {
        final status = await _proxyBridge.startService(port: 8888);
        setState(() => _proxyStatus = status);
        _addLog(
          'تم تشغيل خادم البث على http://${status.localIp}:${status.port}',
        );
      }
    } catch (e) {
      _addLog('خطأ عند تبديل حالة الخادم: $e');
    }
  }

  Future<void> _scanForPs4() async {
    if (_isScanning) return;
    setState(() {
      _isScanning = true;
      _discoveredPs4s = [];
    });

    final localIp = _proxyStatus.localIp;
    final targetPort = _useGoldHenBinLoader ? 9090 : 12800;
    final modeName = _useGoldHenBinLoader ? 'GoldHEN BinLoader (9090)' : 'RPI (12800)';
    _addLog('جاري البحث عن PS4 ($modeName) على شبكة $localIp...');
    try {
      final found = await _rpiClient.scanForPs4(localIp, port: targetPort);
      if (!mounted) return;
      setState(() => _discoveredPs4s = found);
      if (found.isEmpty) {
        _addLog(
          _useGoldHenBinLoader
              ? 'لم يتم العثور على PS4 على المنفذ 9090. تأكد من تفعيل BinLoader Server في إعدادات GoldHEN.'
              : 'لم يتم العثور على PS4 على المنفذ 12800. تأكد من فتح Remote Package Installer.',
        );
      } else {
        _ps4IpController.text = found.first;
        _addLog('تم العثور على PS4: ${found.join(", ")}');
      }
    } catch (e) {
      _addLog('خطأ أثناء البحث عن PS4: $e');
    } finally {
      if (mounted) setState(() => _isScanning = false);
    }
  }

  Future<void> _pasteUrl() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    if (data?.text != null && data!.text!.trim().isNotEmpty) {
      _pkgUrlController.text = data.text!.trim();
      setState(() => _probeResult = null);
      _addLog('تم لصق رابط الـ PKG.');
    }
  }

  Future<void> _probeLink() async {
    final pkgUrl = _pkgUrlController.text.trim();
    if (pkgUrl.isEmpty ||
        (!pkgUrl.startsWith('http://') && !pkgUrl.startsWith('https://'))) {
      _showSnack('يرجى إدخال رابط تحميل صحيح (http أو https) أولاً');
      return;
    }

    setState(() {
      _isProbing = true;
      _probeResult = null;
    });
    _addLog('جاري فحص الرابط والتحقق من ترويسة ملف الـ PKG...');

    try {
      final result = await _proxyBridge.probeUrl(
        url: pkgUrl,
        headers: _buildCustomHeaders(),
      );
      if (!mounted) return;
      setState(() => _probeResult = result);
      _addLog('نتيجة الفحص: ${result.message}');
      if (result.statusCode == 401 || result.statusCode == 403) {
        setState(() => _showAdvancedHeaders = true);
      }
    } catch (e) {
      _addLog('فشل فحص الرابط: $e');
    } finally {
      if (mounted) setState(() => _isProbing = false);
    }
  }

  Future<void> _startInstall() async {
    final ps4Ip = _ps4IpController.text.trim();
    final pkgUrl = _pkgUrlController.text.trim();

    if (ps4Ip.isEmpty) {
      _showSnack('يرجى إدخال عنوان IP الخاص بجهاز PS4');
      return;
    }
    if (pkgUrl.isEmpty ||
        (!pkgUrl.startsWith('http://') && !pkgUrl.startsWith('https://'))) {
      _showSnack('يرجى إدخال رابط تحميل مباشر صحيح (http أو https)');
      return;
    }

    setState(() => _isInstalling = true);

    try {
      // 1. Ensure background Foreground Service proxy is running
      if (!_proxyStatus.running) {
        _addLog('تشغيل خدمة الخلفية تلقائياً...');
        final status = await _proxyBridge.startService(port: 8888);
        setState(() => _proxyStatus = status);
      }

      await _proxyBridge.resetStats();

      if (_useGoldHenBinLoader) {
        // Direct GoldHEN BinLoader (Port 9090) — works on PS4 5.05 - 14.02+
        _addLog('بدء التثبيت المباشر عبر GoldHEN BinLoader ($ps4Ip:9090)...');
        final res = await _proxyBridge.installViaGoldHen(
          ps4Ip: ps4Ip,
          url: pkgUrl,
          headers: _buildCustomHeaders(),
        );
        if (!mounted) return;
        if (res.success) {
          _progressTimer?.cancel();
          setState(() {
            _activeTaskId = null;
            _activeStreamId = res.streamId;
            _activeTitle = res.title.isNotEmpty ? res.title : res.contentId;
            _goldHenTotalSize = res.packageSize;
          });
          _showSnack('تم إرسال اللعبة إلى GoldHEN بنجاح! راقب إشعارات الـ PS4.');
        } else {
          _showSnack('فشل GoldHEN: ${res.error}');
        }
      } else {
        // Legacy RPI (Port 12800)
        final stream = await _proxyBridge.registerUrl(
          url: pkgUrl,
          headers: _buildCustomHeaders(),
        );
        _activeStreamId = stream.id;
        _addLog('تم إنشاء رابط البث المحلي: ${stream.proxyUrl}');

        _addLog('إرسال طلب التثبيت إلى PS4 RPI ($ps4Ip:12800)...');
        final res = await _rpiClient.installPkg(
          ps4Ip: ps4Ip,
          packageUrl: stream.proxyUrl,
        );

        if (res.success && res.taskId != null) {
          setState(() {
            _activeTaskId = res.taskId;
            _activeTitle = res.title ?? stream.fileName;
            _goldHenTotalSize = 0;
          });
          _addLog('بدأ التثبيت بنجاح! رقم المهمة: ${res.taskId} ($_activeTitle)');
          if (res.taskId! >= 0) {
            _startProgressPolling(ps4Ip, res.taskId!);
          } else {
            _addLog('⚠️ ملاحظة: RPI أعاد task_id = -1. يُنصح باستخدام وضع GoldHEN (9090) على إصدارات PS4 الحديثة.');
          }
        } else {
          _addLog('رفض الـ PS4 الطلب: ${res.error}');
          _showSnack('فشل بدء التثبيت: ${res.error}');
        }
      }
    } catch (e) {
      _addLog('خطأ أثناء الاتصال بالـ PS4: $e');
      _showSnack('تعذر الاتصال بجهاز PS4: $e');
    } finally {
      if (mounted) setState(() => _isInstalling = false);
    }
  }

  void _startProgressPolling(String ps4Ip, int taskId) {
    _progressTimer?.cancel();
    _progressTimer = Timer.periodic(const Duration(seconds: 2), (_) async {
      try {
        final prog = await _rpiClient.getTaskProgress(
          ps4Ip: ps4Ip,
          taskId: taskId,
        );
        if (!mounted) return;
        setState(() => _taskProgress = prog);

        if (prog.lengthTotal > 0 && prog.transferredTotal >= prog.lengthTotal) {
          _addLog('اكتمل نقل وتثبيت الملف بنجاح!');
          _progressTimer?.cancel();
        }
      } catch (_) {
        // Ignore transient polling hiccups
      }
    });
  }

  Future<void> _pauseTask() async {
    final taskId = _activeTaskId;
    if (taskId == null) return;
    try {
      final ok = await _rpiClient.pauseTask(
        ps4Ip: _ps4IpController.text.trim(),
        taskId: taskId,
      );
      _addLog(ok ? 'تم إيقاف المهمة #$taskId مؤقتاً.' : 'تعذر إيقاف المهمة.');
    } catch (e) {
      _addLog('خطأ عند الإيقاف المؤقت: $e');
    }
  }

  Future<void> _resumeTask() async {
    final taskId = _activeTaskId;
    if (taskId == null) return;
    try {
      final ok = await _rpiClient.resumeTask(
        ps4Ip: _ps4IpController.text.trim(),
        taskId: taskId,
      );
      _addLog(ok ? 'تم استئناف المهمة #$taskId من نقطة التوقف.' : 'تعذر الاستئناف.');
    } catch (e) {
      _addLog('خطأ عند الاستئناف: $e');
    }
  }

  Future<void> _cancelTask() async {
    final taskId = _activeTaskId;
    if (taskId == null) return;
    try {
      await _rpiClient.stopTask(
        ps4Ip: _ps4IpController.text.trim(),
        taskId: taskId,
      );
      await _rpiClient.unregisterTask(
        ps4Ip: _ps4IpController.text.trim(),
        taskId: taskId,
      );
      if (_activeStreamId != null) {
        await _proxyBridge.unregisterUrl(_activeStreamId!);
      }
      _progressTimer?.cancel();
      setState(() {
        _activeTaskId = null;
        _activeStreamId = null;
        _taskProgress = null;
      });
      _addLog('تم إلغاء المهمة #$taskId وإغلاق البث.');
    } catch (e) {
      _addLog('خطأ عند الإلغاء: $e');
    }
  }

  void _showSnack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  String _formatBytes(int bytes) {
    if (bytes <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB'];
    double size = bytes.toDouble();
    int unit = 0;
    while (size >= 1024 && unit < units.length - 1) {
      size /= 1024;
      unit++;
    }
    return '${size.toStringAsFixed(unit >= 2 ? 2 : 0)} ${units[unit]}';
  }

  String _formatSpeed(int bps) {
    if (bps <= 0) return '0.00 MB/s';
    final mbps = bps / (1024 * 1024);
    return '${mbps.toStringAsFixed(2)} MB/s';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isRunning = _proxyStatus.running;

    return Directionality(
      textDirection: TextDirection.rtl,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('PS4 PKG Background Streamer'),
          centerTitle: true,
          actions: [
            IconButton(
              tooltip: isRunning ? 'إيقاف الخادم' : 'تشغيل الخادم',
              onPressed: _toggleService,
              icon: Icon(
                isRunning ? Icons.stop_circle_outlined : Icons.play_circle_outline,
                color: isRunning ? Colors.greenAccent : Colors.amberAccent,
              ),
            ),
          ],
        ),
        body: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            // 1. Background Service Status Card
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Icon(
                          isRunning ? Icons.wifi_tethering : Icons.wifi_tethering_off,
                          color: isRunning ? Colors.greenAccent : Colors.grey,
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            isRunning
                                ? 'خادم البث يعمل في الخلفية'
                                : 'خادم البث متوقف حالياً',
                            style: theme.textTheme.titleMedium?.copyWith(
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ),
                        FilledButton.tonal(
                          onPressed: _toggleService,
                          child: Text(isRunning ? 'إيقاف' : 'تشغيل'),
                        ),
                      ],
                    ),
                    const SizedBox(height: 12),
                    Wrap(
                      spacing: 12,
                      runSpacing: 8,
                      children: [
                        _StatChip(
                          label: 'عنوان الجوال',
                          value: '${_proxyStatus.localIp}:${_proxyStatus.port}',
                        ),
                        _StatChip(
                          label: 'السرعة',
                          value: _formatSpeed(_proxyStatus.bytesPerSecond),
                        ),
                        _StatChip(
                          label: 'المنقول',
                          value: _formatBytes(_proxyStatus.totalBytesTransferred),
                        ),
                        _StatChip(
                          label: 'الاتصالات',
                          value: '${_proxyStatus.activeConnections}',
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),

            // 2. PS4 Connection Card
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'اتصال جهاز PS4',
                      style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 10),
                    SizedBox(
                      width: double.infinity,
                      child: SegmentedButton<bool>(
                        segments: const [
                          ButtonSegment<bool>(
                            value: true,
                            label: Text('GoldHEN (9090)'),
                            icon: Icon(Icons.bolt, size: 16),
                          ),
                          ButtonSegment<bool>(
                            value: false,
                            label: Text('RPI (12800)'),
                            icon: Icon(Icons.apps, size: 16),
                          ),
                        ],
                        selected: {_useGoldHenBinLoader},
                        onSelectionChanged: (sel) {
                          setState(() => _useGoldHenBinLoader = sel.first);
                        },
                      ),
                    ),
                    const SizedBox(height: 6),
                    Text(
                      _useGoldHenBinLoader
                          ? 'موصى به لإصدارات PS4 9.00–14.02+: فعّل BinLoader Server من إعدادات GoldHEN (لا تحتاج لفتح RPI).'
                          : 'الوضع القديم: يتطلب فتح تطبيق Remote Package Installer على الـ PS4.',
                      style: const TextStyle(fontSize: 12, color: Colors.grey),
                    ),
                    const SizedBox(height: 12),
                    Row(
                      children: [
                        Expanded(
                          child: TextField(
                            controller: _ps4IpController,
                            textDirection: TextDirection.ltr,
                            keyboardType: TextInputType.number,
                            decoration: const InputDecoration(
                              labelText: 'عنوان IP لجهاز PS4',
                              hintText: '192.168.1.100',
                              border: OutlineInputBorder(),
                              isDense: true,
                            ),
                          ),
                        ),
                        const SizedBox(width: 8),
                        FilledButton.icon(
                          onPressed: _isScanning ? null : _scanForPs4,
                          icon: _isScanning
                              ? const SizedBox(
                                  width: 16,
                                  height: 16,
                                  child: CircularProgressIndicator(strokeWidth: 2),
                                )
                              : const Icon(Icons.radar),
                          label: const Text('بحث تلقائي'),
                        ),
                      ],
                    ),
                    if (_discoveredPs4s.isNotEmpty) ...[
                      const SizedBox(height: 10),
                      Wrap(
                        spacing: 8,
                        children: _discoveredPs4s
                            .map(
                              (ip) => ActionChip(
                                avatar: const Icon(Icons.gamepad, size: 16),
                                label: Text(ip),
                                onPressed: () =>
                                    setState(() => _ps4IpController.text = ip),
                              ),
                            )
                            .toList(),
                      ),
                    ],
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),

            // 3. PKG Direct Link Card
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'رابط ملف الـ PKG المباشر',
                      style: theme.textTheme.titleMedium?.copyWith(
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                    const SizedBox(height: 12),
                    TextField(
                      controller: _pkgUrlController,
                      textDirection: TextDirection.ltr,
                      maxLines: 2,
                      minLines: 1,
                      decoration: InputDecoration(
                        labelText: 'رابط التحميل (HTTP / HTTPS)',
                        hintText: 'https://example.com/game.pkg',
                        border: const OutlineInputBorder(),
                        suffixIcon: IconButton(
                          tooltip: 'لصق الرابط',
                          icon: const Icon(Icons.content_paste),
                          onPressed: _pasteUrl,
                        ),
                      ),
                    ),
                    if (_probeResult != null) ...[
                      const SizedBox(height: 10),
                      Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(10),
                        decoration: BoxDecoration(
                          color: _probeResult!.ok
                              ? Colors.green.withValues(alpha: 0.18)
                              : Colors.orange.withValues(alpha: 0.18),
                          borderRadius: BorderRadius.circular(8),
                          border: Border.all(
                            color: _probeResult!.ok
                                ? Colors.greenAccent
                                : Colors.orangeAccent,
                          ),
                        ),
                        child: Text(
                          _probeResult!.message,
                          style: const TextStyle(fontSize: 13),
                        ),
                      ),
                    ],
                    const SizedBox(height: 8),
                    InkWell(
                      onTap: () => setState(
                        () => _showAdvancedHeaders = !_showAdvancedHeaders,
                      ),
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: 6),
                        child: Row(
                          children: [
                            Icon(
                              _showAdvancedHeaders
                                  ? Icons.expand_less
                                  : Icons.expand_more,
                              size: 20,
                            ),
                            const SizedBox(width: 6),
                            const Text('كلمة المرور / الترويسات المتقدمة (Auth / Cookie)'),
                          ],
                        ),
                      ),
                    ),
                    if (_showAdvancedHeaders) ...[
                      const SizedBox(height: 8),
                      Row(
                        children: [
                          Expanded(
                            child: TextField(
                              controller: _usernameController,
                              textDirection: TextDirection.ltr,
                              decoration: const InputDecoration(
                                labelText: 'اسم المستخدم (اختياري)',
                                border: OutlineInputBorder(),
                                isDense: true,
                              ),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: TextField(
                              controller: _passwordController,
                              textDirection: TextDirection.ltr,
                              obscureText: true,
                              decoration: const InputDecoration(
                                labelText: 'كلمة المرور (اختياري)',
                                border: OutlineInputBorder(),
                                isDense: true,
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      TextField(
                        controller: _cookieController,
                        textDirection: TextDirection.ltr,
                        decoration: const InputDecoration(
                          labelText: 'Cookie (اختياري)',
                          border: OutlineInputBorder(),
                          isDense: true,
                        ),
                      ),
                      const SizedBox(height: 8),
                      TextField(
                        controller: _refererController,
                        textDirection: TextDirection.ltr,
                        decoration: const InputDecoration(
                          labelText: 'Referer (اختياري)',
                          border: OutlineInputBorder(),
                          isDense: true,
                        ),
                      ),
                      const SizedBox(height: 8),
                      TextField(
                        controller: _userAgentController,
                        textDirection: TextDirection.ltr,
                        decoration: const InputDecoration(
                          labelText: 'User-Agent (اختياري)',
                          border: OutlineInputBorder(),
                          isDense: true,
                        ),
                      ),
                    ],
                    const SizedBox(height: 14),
                    Row(
                      children: [
                        OutlinedButton.icon(
                          onPressed: _isProbing ? null : _probeLink,
                          icon: _isProbing
                              ? const SizedBox(
                                  width: 16,
                                  height: 16,
                                  child: CircularProgressIndicator(strokeWidth: 2),
                                )
                              : const Icon(Icons.verified_outlined),
                          label: const Text('فحص الرابط'),
                        ),
                        const SizedBox(width: 8),
                        Expanded(
                          child: SizedBox(
                            height: 48,
                            child: FilledButton.icon(
                              onPressed: _isInstalling ? null : _startInstall,
                              icon: _isInstalling
                                  ? const SizedBox(
                                      width: 18,
                                      height: 18,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                      ),
                                    )
                                  : const Icon(Icons.download_for_offline),
                              label: const Text(
                                'بدء التثبيت المباشر على PS4',
                                style: TextStyle(
                                  fontSize: 15,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),

            // 4A. GoldHEN Live Proxy Transfer Card
            if (_goldHenTotalSize > 0) ...[
              const SizedBox(height: 12),
              Card(
                color: theme.colorScheme.surfaceContainerHighest,
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'بث GoldHEN النشط — ${_activeTitle ?? ""}',
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 10),
                      LinearProgressIndicator(
                        value: (_proxyStatus.totalBytesTransferred /
                                _goldHenTotalSize)
                            .clamp(0.0, 1.0),
                        minHeight: 8,
                        borderRadius: BorderRadius.circular(4),
                      ),
                      const SizedBox(height: 8),
                      Row(
                        mainAxisAlignment: MainAxisAlignment.spaceBetween,
                        children: [
                          Text(
                            '${((_proxyStatus.totalBytesTransferred / _goldHenTotalSize).clamp(0.0, 1.0) * 100).toStringAsFixed(1)}% • ${_formatSpeed(_proxyStatus.bytesPerSecond)}',
                            style: const TextStyle(fontWeight: FontWeight.bold),
                          ),
                          Text(
                            '${_formatBytes(_proxyStatus.totalBytesTransferred)} / ${_formatBytes(_goldHenTotalSize)}',
                            textDirection: TextDirection.ltr,
                          ),
                        ],
                      ),
                      const SizedBox(height: 6),
                      const Text(
                        'يمكنك الإيقاف المؤقت والاستئناف مباشرة من الإشعارات ← التنزيلات في جهاز PS4.',
                        style: TextStyle(fontSize: 12, color: Colors.grey),
                      ),
                    ],
                  ),
                ),
              ),
            ],

            // 4B. Active RPI Task Progress Card
            if (_activeTaskId != null) ...[
              const SizedBox(height: 12),
              Card(
                color: theme.colorScheme.surfaceContainerHighest,
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'المهمة النشطة #$_activeTaskId — ${_activeTitle ?? ""}',
                        style: theme.textTheme.titleMedium?.copyWith(
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 10),
                      LinearProgressIndicator(
                        value: _taskProgress?.progress,
                        minHeight: 8,
                        borderRadius: BorderRadius.circular(4),
                      ),
                      const SizedBox(height: 8),
                      if (_taskProgress != null)
                        Row(
                          mainAxisAlignment: MainAxisAlignment.spaceBetween,
                          children: [
                            Text(
                              '${(_taskProgress!.progress * 100).toStringAsFixed(1)}%',
                              style: const TextStyle(fontWeight: FontWeight.bold),
                            ),
                            Text(
                              '${_formatBytes(_taskProgress!.transferredTotal)} / ${_formatBytes(_taskProgress!.lengthTotal)}',
                            ),
                          ],
                        ),
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          Expanded(
                            child: OutlinedButton.icon(
                              onPressed: _pauseTask,
                              icon: const Icon(Icons.pause),
                              label: const Text('إيقاف مؤقت'),
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: FilledButton.tonalIcon(
                              onPressed: _resumeTask,
                              icon: const Icon(Icons.play_arrow),
                              label: const Text('استئناف'),
                            ),
                          ),
                          const SizedBox(width: 8),
                          IconButton.filledTonal(
                            tooltip: 'إلغاء المهمة',
                            onPressed: _cancelTask,
                            icon: const Icon(Icons.close, color: Colors.redAccent),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ],

            const SizedBox(height: 12),

            // 5. Activity Log Card
            Card(
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      mainAxisAlignment: MainAxisAlignment.spaceBetween,
                      children: [
                        Text(
                          'سجل العمليات',
                          style: theme.textTheme.titleSmall?.copyWith(
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        if (_logs.isNotEmpty)
                          TextButton(
                            onPressed: () => setState(() => _logs.clear()),
                            child: const Text('مسح'),
                          ),
                      ],
                    ),
                    const SizedBox(height: 6),
                    if (_logs.isEmpty)
                      const Text(
                        'لا توجد عمليات بعد. قم بلصق رابط PKG واضغط فحص الرابط أو بدء التثبيت.',
                        style: TextStyle(color: Colors.grey, fontSize: 13),
                      )
                    else
                      ..._logs.take(20).map(
                            (log) => Padding(
                              padding: const EdgeInsets.symmetric(vertical: 2),
                              child: Text(
                                log,
                                style: const TextStyle(
                                  fontFamily: 'monospace',
                                  fontSize: 12,
                                ),
                              ),
                            ),
                          ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _StatChip extends StatelessWidget {
  final String label;
  final String value;

  const _StatChip({required this.label, required this.value});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: Theme.of(context).colorScheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            label,
            style: const TextStyle(fontSize: 11, color: Colors.grey),
          ),
          Text(
            value,
            textDirection: TextDirection.ltr,
            style: const TextStyle(fontSize: 13, fontWeight: FontWeight.bold),
          ),
        ],
      ),
    );
  }
}
