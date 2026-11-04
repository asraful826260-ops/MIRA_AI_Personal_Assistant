# MIRA AI Android (Gemini সংযুক্ত সংস্করণ)

এই প্রজেক্টে MIRA-র Android চ্যাট UI, বাংলা ভয়েস ইনপুট/TTS এবং Gemini API চ্যাট যুক্ত আছে। API Key অ্যাপের কোডে রাখা নেই; অ্যাপের ⚙️ Settings থেকে ব্যবহারকারী নিজে Key সংরক্ষণ করবেন (Android SharedPreferences-এ লোকালি)।

## APK তৈরি (GitHub Actions দিয়ে)
1. ZIP ফাইলটি Extract করুন।
2. পুরো ফোল্ডারের ফাইল GitHub repository-র `main` branch-এ upload/push করুন (আগের ফাইল replace করুন)।
3. GitHub repository → **Actions** → **MIRA Android APK Build** → **Run workflow** চাপুন।
4. Build সফল হলে workflow run-এর নিচে **Artifacts** থেকে `MIRA-AI-APK` ZIP ডাউনলোড করুন।
5. ZIP Extract করে `app-debug.apk` ফোনে নিন এবং Install করুন। Android চাইলে browser/file manager-এর জন্য “Install unknown apps” অনুমতি দিন।

## Gemini API Key সেট করা
1. Google AI Studio থেকে নিজের Gemini API Key তৈরি করুন।
2. MIRA অ্যাপ খুলে উপরের ⚙️ চাপুন।
3. API Key বসিয়ে **সংরক্ষণ** চাপুন।
4. এরপর বাংলায় প্রশ্ন লিখে Send চাপুন বা **কথা বলুন** ব্যবহার করুন।

API Key কাউকে পাঠাবেন না, GitHub-এ commit করবেন না, এবং screenshot-এ প্রকাশ করবেন না। এই অ্যাপের সরাসরি Gemini API call-এ Key ডিভাইসেই থাকে; ব্যক্তিগত/প্রকাশ্য distribution-এর জন্য নিরাপদ backend ব্যবহার করা উত্তম, কারণ APK reverse engineer করলে client-side key ঝুঁকিতে পড়তে পারে।

## নোট
- Gemini মডেল: `gemini-2.5-flash`। Google API-তে মডেলের প্রাপ্যতা/কোটা সময়ের সঙ্গে বদলাতে পারে।
- API ব্যবহারে Google-এর quota ও billing নীতি প্রযোজ্য।
- ভয়েস ইনপুট Android speech recognition service-এর ওপর নির্ভর করে।
- Wi-Fi, Bluetooth, Settings, YouTube, flashlight, volume ইত্যাদি device shortcut-ও রাখা হয়েছে।
