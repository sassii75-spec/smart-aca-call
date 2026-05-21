import React, { useEffect, useState, useRef } from 'react';
import {
  SafeAreaView,
  StatusBar,
  StyleSheet,
  PermissionsAndroid,
  NativeModules,
  Alert,
  View,
  ActivityIndicator,
  Platform
} from 'react-native';
import { WebView } from 'react-native-webview';

const { CallRecordingAgent } = NativeModules;

// 하드코딩된 기본 학원 ID (향후 웹뷰와 통신하여 동적으로 변경 가능)
const DEFAULT_ACADEMY_ID = 'sa_academy';
const TARGET_URL = 'https://smart-call-ai-gamma.vercel.app/';

// Bypasses Google OAuth 'disallowed_useragent' security policy in WebView by presenting standard browser user agents
const USER_AGENT = Platform.OS === 'android'
  ? 'Mozilla/5.0 (Linux; Android 13; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/112.0.0.0 Mobile Safari/537.36'
  : 'Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1';

function App(): React.JSX.Element {
  const [isReady, setIsReady] = useState(false);
  const webviewRef = useRef<WebView>(null);

  useEffect(() => {
    async function initializeApp() {
      // 1. 기본 시스템 권한 요청
      const hasBasicPermission = await requestPermissions();
      
      if (!hasBasicPermission) {
        Alert.alert(
          '기본 권한 필요', 
          '통화 녹음 파일 감지를 위해 오디오 및 알림 접근 권한이 필수적입니다.',
          [{ text: '확인' }]
        );
      }
      
      // 2. 안드로이드 11 이상일 경우 Scoped Storage 우회를 위한 '모든 파일 접근 권한' 추가 체크
      if (Platform.OS === 'android') {
        try {
          const hasAllFilesAccess = await CallRecordingAgent.hasManageExternalStoragePermission();
          if (!hasAllFilesAccess) {
            Alert.alert(
              '모든 파일 관리 권한 필요',
              '안드로이드 11 이상 기기에서는 다른 앱(전화 앱)이 기록한 통화 녹음 파일을 가져오기 위해 "모든 파일 관리 권한"이 반드시 필요합니다.\n\n확인을 누르시면 설정 화면으로 이동합니다. "Smart Aca Call" 앱의 스위치를 켜서 허용해 주세요.',
              [
                { 
                  text: '설정으로 이동', 
                  onPress: () => {
                    CallRecordingAgent.requestManageExternalStoragePermission();
                  } 
                },
                {
                  text: '나중에',
                  style: 'cancel'
                }
              ]
            );
          } else {
            // 권한이 모두 갖춰진 상태라면 에이전트 시작
            CallRecordingAgent.startAgent(DEFAULT_ACADEMY_ID);
          }
        } catch (e) {
          console.warn("Failed to check manage storage permission, starting agent directly:", e);
          CallRecordingAgent.startAgent(DEFAULT_ACADEMY_ID);
        }
      } else {
        try {
          CallRecordingAgent.startAgent(DEFAULT_ACADEMY_ID);
        } catch (e) {
          console.warn("Failed to start agent:", e);
        }
      }
      
      // 권한 응답과 무관하게 웹뷰는 띄움
      setIsReady(true);
    }

    initializeApp();
  }, []);

  const requestPermissions = async () => {
    try {
      const grants = await PermissionsAndroid.requestMultiple([
        PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE,
        PermissionsAndroid.PERMISSIONS.READ_MEDIA_AUDIO,
        PermissionsAndroid.PERMISSIONS.POST_NOTIFICATIONS
      ]);

      return (
        grants[PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE] === PermissionsAndroid.RESULTS.GRANTED ||
        grants[PermissionsAndroid.PERMISSIONS.READ_MEDIA_AUDIO] === PermissionsAndroid.RESULTS.GRANTED
      );
    } catch (err) {
      console.warn(err);
      return false;
    }
  };

  if (!isReady) {
    return (
      <View style={styles.loadingContainer}>
        <ActivityIndicator size="large" color="#3B82F6" />
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <StatusBar barStyle="dark-content" backgroundColor="#ffffff" translucent={false} />
      <WebView
        ref={webviewRef}
        source={{ uri: TARGET_URL }}
        style={styles.webview}
        javaScriptEnabled={true}
        domStorageEnabled={true}
        startInLoadingState={true}
        userAgent={USER_AGENT}
        renderLoading={() => (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" color="#3B82F6" />
          </View>
        )}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#ffffff',
    paddingTop: Platform.OS === 'android' ? StatusBar.currentHeight : 0,
  },
  webview: {
    flex: 1,
  },
  loadingContainer: {
    ...StyleSheet.absoluteFill,
    backgroundColor: '#ffffff',
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: 10,
  }
});

export default App;
