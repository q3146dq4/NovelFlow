# NovelRegEx GitHub Release / 자동 업데이트 설정

NovelRegEx의 앱 내 업데이트 기능은 GitHub Releases의 `q3146dq4/NovelRegEx` 최신 릴리스를 조회합니다.

## 1. 릴리스 서명 키 만들기

업데이트 APK는 기존 설치본과 **같은 Android 서명 키**로 서명되어야 합니다. 한 번 정한 release keystore를 앞으로 모든 NovelRegEx 배포에 계속 사용하세요.

예시:

```powershell
keytool -genkeypair -v -keystore NovelRegEx-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias NovelRegEx -storepass "YOUR_STORE_PASSWORD" -keypass "YOUR_KEY_PASSWORD" -dname "CN=NovelRegEx, OU=Release, O=NovelRegEx, L=Seoul, C=KR"
```

`NovelRegEx-release.jks`는 저장소에 커밋하지 마세요.

## 2. GitHub repository secrets

`q3146dq4/NovelRegEx` → Settings → Secrets and variables → Actions → New repository secret에서 다음 4개를 등록하세요.

- `NovelRegEx_KEYSTORE_BASE64`
- `NovelRegEx_KEY_ALIAS`
- `NovelRegEx_KEYSTORE_PASSWORD`
- `NovelRegEx_KEY_PASSWORD`

Base64 생성 예시:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\NovelRegEx-release.jks"))
```

출력된 한 줄 전체를 `NovelRegEx_KEYSTORE_BASE64`에 넣습니다.

## 3. Gradle 환경 변수

로컬 release 빌드가 필요하면 다음 환경 변수를 설정합니다.

- `KEYSTORE_PATH`
- `KEY_ALIAS`
- `KEYSTORE_PASSWORD`
- `KEY_PASSWORD` (생략하면 store password 사용)

## 4. 첫 릴리스

현재 버전이 `0.1`이므로 태그를 `v0.1`로 생성하고 push합니다.

```bash
git add .
git commit -m "Prepare NovelRegEx v0.1 release"
git tag v0.1
git push origin main --tags
```

GitHub Actions가 signed APK를 빌드하고 `NovelRegEx-v0.1.apk`를 Release asset으로 올립니다.

## 5. 다음 버전

`gradle.properties`에서 두 값을 증가시키세요.

```properties
app.versionCode=2
app.versionName=0.2
```

그 후 `v0.2` 태그를 생성합니다.

```bash
git add gradle.properties
git commit -m "Bump version to 0.2"
git tag v0.2
git push origin main --tags
```

앱은 `https://api.github.com/repos/q3146dq4/NovelRegEx/releases/latest`를 조회하여 더 높은 버전을 찾고, Release의 `.apk` asset을 다운로드합니다.

## 6. 중요: debug APK와 release APK를 섞지 않기

GitHub Actions의 release APK와 로컬 `assembleDebug` APK는 일반적으로 서로 다른 키로 서명됩니다. 이미 설치된 release APK를 업데이트하려면 같은 release keystore가 필요합니다.

따라서 실제 사용자 배포본은 항상 GitHub Release에서 만든 signed APK를 기준으로 하세요.
