# SecureSimpleKeyboard

<img src="images/screenshot-0.png"
      alt="closeup"
      width="500"/>
      
# EN | About
This is the SecureSimpleKeyboard app — a fork of Simple Keyboard with improved security.

It blocks screenshots in the keyboard window, and also has a Duress Password feature to reset data after sending any code of a preset length, different from the main password, to the system by setting a minimal attempts limit.

In the keyboard settings, it uses the old name for masking on the lock screen.

Also, on entering bypassing this app, the attempts limit will remain minimal. It will be temporarily increased only on entering a different characters number via this keyboard. To avoid accidental reset, make sure that the your password length is significantly different from the preset length for reset, even so it does not matter whether it is longer or shorter. The minimal preset length for reset is 4, as the Android system on the lock screen counts an incorrect attempt only if the entry is longer than 4.

[🏷️ Releases (Download apk)](/../../releases/latest)

# RU | Описание
Это приложение SecureSimpleKeyboard - форк Simple Keyboard с улучшенной безопасностью.

Оно блокирует скриншоты в окне клавиатуры, а также имеет функцию Duress Password для сброса данных после отправки системе любого кода заданной длины отличного от основного пароля за счет установки минимального лимита попыток. 

В настройках клавиатур имеет старое название для маскировки на экране блокировки.

Также и при вводе в обход этого приложения лимит попыток останется минимальным. Он будет временно увеличен только при вводе другого количества символов через эту клавиатуру. Для избежания случайного сброса убедитесь что длина вашего пароля значительно отличается от заданной длины для сброса, при этом не важно в большую или меньшую сторону. Минимальная задаваемая длина для сброса это 4, так как система Android на экране блокировки засчитывает неверную попытку только если ввод длиннее 4.

[🏷️ Релизы (Скачать apk)](/../../releases/latest)

## Credits

Licensed under Apache License Version 2

This keyboard is based on AOSP LatinIME keyboard. You can get the original source code in https://android.googlesource.com/platform/packages/inputmethods/LatinIME/

Also it based on Simple Keyboard:
https://github.com/rkkr/simple-keyboard/
