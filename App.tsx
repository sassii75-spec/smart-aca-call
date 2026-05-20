import React, { useState, useEffect } from 'react';
import {
  SafeAreaView,
  StatusBar,
  StyleSheet,
  Text,
  View,
  TextInput,
  TouchableOpacity,
  PermissionsAndroid,
  NativeModules,
  Alert
} from 'react-native';

const { CallRecordingAgent } = NativeModules;

function App(): React.JSX.Element {
  const [academyId, setAcademyId] = useState('sa_academy');
  const [isAgentRunning, setIsAgentRunning] = useState(false);

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

  const toggleAgent = async () => {
    if (isAgentRunning) {
      CallRecordingAgent.stopAgent();
      setIsAgentRunning(false);
      Alert.alert('종료됨', '자동 업로드 에이전트가 중지되었습니다.');
    } else {
      const hasPermission = await requestPermissions();
      if (!hasPermission) {
        Alert.alert('권한 필요', '통화 녹음 파일을 감지하려면 저장소 권한이 필요합니다.');
        return;
      }
      
      CallRecordingAgent.startAgent(academyId);
      setIsAgentRunning(true);
      Alert.alert('실행됨', '백그라운드에서 녹음 파일을 감시합니다.');
    }
  };

  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="dark-content" backgroundColor="#F9FAFB" />
      <View style={styles.content}>
        
        <View style={styles.header}>
          <Text style={styles.title}>Smart Call Agent</Text>
          <Text style={styles.subtitle}>통화 녹음 자동 업로더</Text>
        </View>

        <View style={styles.card}>
          <Text style={styles.label}>학원 아이디 (Academy ID)</Text>
          <TextInput
            style={styles.input}
            value={academyId}
            onChangeText={setAcademyId}
            placeholder="학원 아이디 입력 (예: sa_academy)"
            placeholderTextColor="#9CA3AF"
          />
          
          <TouchableOpacity 
            style={[styles.button, isAgentRunning ? styles.buttonStop : styles.buttonStart]} 
            onPress={toggleAgent}
          >
            <Text style={styles.buttonText}>
              {isAgentRunning ? '에이전트 중지하기' : '자동 업로드 시작하기'}
            </Text>
          </TouchableOpacity>
        </View>

        <View style={styles.footer}>
          <Text style={styles.statusText}>
            상태: {isAgentRunning ? '🟢 모니터링 중' : '⚪ 대기 중'}
          </Text>
          <Text style={styles.infoText}>
            이 앱은 백그라운드에서 실행되며 통화 종료 시 생성되는 파일을 서버로 즉시 전송합니다.
          </Text>
        </View>

      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F9FAFB',
  },
  content: {
    flex: 1,
    padding: 24,
    justifyContent: 'center',
  },
  header: {
    alignItems: 'center',
    marginBottom: 48,
  },
  title: {
    fontSize: 28,
    fontWeight: 'bold',
    color: '#111827',
    marginBottom: 8,
  },
  subtitle: {
    fontSize: 16,
    color: '#6B7280',
  },
  card: {
    backgroundColor: 'white',
    borderRadius: 16,
    padding: 24,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.05,
    shadowRadius: 12,
    elevation: 3,
  },
  label: {
    fontSize: 14,
    fontWeight: '600',
    color: '#374151',
    marginBottom: 8,
  },
  input: {
    backgroundColor: '#F3F4F6',
    borderWidth: 1,
    borderColor: '#E5E7EB',
    borderRadius: 8,
    padding: 16,
    fontSize: 16,
    color: '#111827',
    marginBottom: 24,
  },
  button: {
    padding: 16,
    borderRadius: 8,
    alignItems: 'center',
  },
  buttonStart: {
    backgroundColor: '#3B82F6',
  },
  buttonStop: {
    backgroundColor: '#EF4444',
  },
  buttonText: {
    color: 'white',
    fontSize: 16,
    fontWeight: 'bold',
  },
  footer: {
    marginTop: 48,
    alignItems: 'center',
  },
  statusText: {
    fontSize: 16,
    fontWeight: 'bold',
    color: '#374151',
    marginBottom: 12,
  },
  infoText: {
    textAlign: 'center',
    fontSize: 13,
    color: '#9CA3AF',
    lineHeight: 20,
  }
});

export default App;
