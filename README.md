# Password Manager

Ứng dụng quản lý mật khẩu cá nhân trên Android, áp dụng mô hình
Zero-Knowledge Encryption: toàn bộ dữ liệu được mã hóa AES-256-GCM
ngay trên thiết bị trước khi đồng bộ lên Firebase Firestore.

## Tính năng chính
- Mã hóa client-side bằng AES-GCM, khóa dẫn xuất qua PBKDF2
- Xác thực đa lớp: Master Password, mã PIN, sinh trắc học
- Tự động điền mật khẩu (Android Autofill Framework)
- Đồng bộ đa thiết bị, hoạt động offline-first, giải quyết xung đột
  bằng Last-Write-Wins
- Sinh mật khẩu ngẫu nhiên và đánh giá độ mạnh

## Công nghệ sử dụng
Java, Android Studio, Room (SQLite), Firebase Authentication,
Cloud Firestore, Android Keystore, BiometricPrompt API.

## Cài đặt và chạy thử
Xem chi tiết tại mục 3.1 của báo cáo, hoặc tóm tắt:
1. Mở project bằng Android Studio (minSdk 28, Gradle 9.5.0).
2. Tạo project Firebase, bật Authentication (Email/Password) và
   Firestore, tải `google-services.json` đặt vào thư mục `app/`.
3. Sync Gradle rồi Run.

## Tác giả
Nguyễn Ngọc Khánh – AT200429 – Học viện Kỹ thuật Mật mã
GVHD: TS. Nguyễn Mạnh Thắng