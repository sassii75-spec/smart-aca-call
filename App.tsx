import React, { useEffect, useState, useRef } from 'react';
import {
  SafeAreaView,
  StatusBar,
  StyleSheet,
  PermissionsAndroid,
  NativeModules,
  Alert,
  View,
  ActivityIndicator
} from 'react-native';
import { WebView } from 'react-native-webview';

const { CallRecordingAgent } = NativeModules;

// 하드코딩된 기본 학원 ID (향후 웹뷰와 통신하여 동적으로 변경 가능)
const DEFAULT_ACADEMY_ID = 'sa_academy';
const TARGET_URL = 'https://smart-call-ai.vercel.app/';

function App(): React.JSX.Element {
  const [isReady, setIsReady] = useState(false);
  const webviewRef = useRef<WebView>(null);

  useEffect(() => {
    async function initializeApp() {
      // 1. 권한 요청
      const hasPermission = await requestPermissions();
      
      if (!hasPermission) {
        Alert.alert(
          '권한 필요', 
          '통화 녹음 파일 자동 업로드를 위해 저장소 및 오디오 접근 권한이 필수적입니다.',
          [{ text: '확인' }]
        );
      } else {
        // 2. 권한이 있으면 백그라운드 요원(Agent) 조용히 시작
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
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="dark-content" backgroundColor="#ffffff" />
      <WebView
        ref={webviewRef}
        source={{ uri: TARGET_URL }}
        style={styles.webview}
        javaScriptEnabled={true}
        domStorageEnabled={true}
        startInLoadingState={true}
        renderLoading={() => (
          <View style={styles.loadingContainer}>
            <ActivityIndicator size="large" color="#3B82F6" />
          </View>
        )}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#ffffff',
  },
  webview: {
    flex: 1,
  },
  loadingContainer: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: '#ffffff',
    justifyContent: 'center',
    alignItems: 'center',
    zIndex: 10,
  }
});

export default App;
