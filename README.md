# Pull-Up Counter MVP 0.2

Android-приложение на Java для подсчёта подтягиваний по позе человека.

## Что уже есть
- Camera2 без CameraX;
- MediaPipe Pose Landmarker Lite;
- фронтальная камера;
- 33 точки скелета;
- углы обоих локтей;
- ручная калибровка перекладины касанием по экрану;
- автомат `WAITING -> DOWN -> GOING_UP -> UP -> GOING_DOWN`;
- счётчик повторений;
- кнопки **Перекладина** и **Сброс**.

## CI / APK build
GitHub Actions workflow `.github/workflows/build-apk.yml` собирает debug APK на каждом push в `main` и сохраняет `app-debug.apk` как artifact `PullUpCounter-debug`.
