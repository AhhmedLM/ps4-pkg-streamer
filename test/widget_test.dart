import 'package:flutter_test/flutter_test.dart';
import 'package:ps4_pkg_streamer/main.dart';

void main() {
  testWidgets('HomeScreen renders core UI elements', (WidgetTester tester) async {
    await tester.pumpWidget(const Ps4PkgStreamerApp());
    expect(find.text('PS4 PKG Background Streamer'), findsOneWidget);
    expect(find.text('بدء التثبيت المباشر على PS4'), findsOneWidget);
    expect(find.text('بحث تلقائي'), findsOneWidget);
  });
}
