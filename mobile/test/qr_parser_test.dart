import 'package:fire_platform_mobile/core/qr_parser.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('keeps an exact bare serial and decodes a recognised URL field', () {
    expect(parseQrSerial('C123-456').serial, 'C123-456');
    expect(parseQrSerial('https://label.invalid/activate?deviceSerial=C123%2D456').serial, 'C123-456');
    expect(parseQrSerial('SN:C123-456').serial, 'C123-456');
  });

  test('does not mistake unrelated URLs, arbitrary keys or ambiguous labels for serials', () {
    for (final text in [
      'https://label.invalid/download',
      'https://label.invalid/?id=C123456',
      'https://label.invalid/?sn=one&sn=two',
      'https://label.invalid/?deviceSerial=one&sn=two',
      'https://label.invalid/?sn=one%0Atwo',
      'other:C123456',
      'javascript:alert(1)',
    ]) {
      expect(() => parseQrSerial(text), throwsA(isA<QrParseFailure>()), reason: text);
    }
  });
}
