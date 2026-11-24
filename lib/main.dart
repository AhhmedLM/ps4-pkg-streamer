import 'package:material_ui/material_ui.dart';

import 'ui/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const Ps4PkgStreamerApp());
}

class Ps4PkgStreamerApp extends StatelessWidget {
  const Ps4PkgStreamerApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'PS4 PKG Streamer',
      debugShowCheckedModeBanner: false,
      themeMode: ThemeMode.dark,
      darkTheme: ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        colorSchemeSeed: const Color(0xFF0070D1), // PlayStation Blue
      ),
      home: const HomeScreen(),
    );
  }
}
