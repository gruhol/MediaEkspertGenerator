pipeline {
    agent any

    environment {
        IMAGE_NAME = 'media-ekspert-generator'
        // Adres i użytkownik serwera — ustaw jako parametry lub zmień tutaj
        DEPLOY_SERVER_HOST        = 'your-server-ip-or-hostname'
        DEPLOY_SERVER_USER        = 'ubuntu'
        DEPLOY_DIR                = '/opt/media-ekspert'
        // ID credentials SSH do serwera (Jenkins → Manage Jenkins → Credentials)
        DEPLOY_SERVER_CREDENTIALS = 'deploy-server-ssh'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Copy files to server') {
            steps {
                sshagent(credentials: [DEPLOY_SERVER_CREDENTIALS]) {
                    sh """
                        ssh -o StrictHostKeyChecking=no ${DEPLOY_SERVER_USER}@${DEPLOY_SERVER_HOST} \
                            'mkdir -p ${DEPLOY_DIR}'
                        scp -r . ${DEPLOY_SERVER_USER}@${DEPLOY_SERVER_HOST}:${DEPLOY_DIR}/
                    """
                }
            }
        }

        stage('Build & Run') {
            steps {
                sshagent(credentials: [DEPLOY_SERVER_CREDENTIALS]) {
                    sh """
                        ssh -o StrictHostKeyChecking=no ${DEPLOY_SERVER_USER}@${DEPLOY_SERVER_HOST} \
                            'cd ${DEPLOY_DIR} && \
                             docker compose down && \
                             docker compose build --no-cache && \
                             docker compose up -d'
                    """
                }
            }
        }
    }

    post {
        success {
            echo "Wdrozenie zakonczone. Aplikacja dziala na porcie 8089."
        }
        failure {
            echo "Pipeline zakonczony bledem. Sprawdz logi powyzej."
        }
    }
}