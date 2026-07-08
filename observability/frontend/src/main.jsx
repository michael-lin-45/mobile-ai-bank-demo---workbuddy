import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { ConfigProvider } from 'antd';
import AppLayout from './components/AppLayout';
import { AppProvider } from './context/AppContext';
import antdTheme from './theme/antdTheme';
import 'antd/dist/reset.css';

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <ConfigProvider theme={antdTheme}>
        <AppProvider>
          <AppLayout />
        </AppProvider>
      </ConfigProvider>
    </BrowserRouter>
  </React.StrictMode>,
);
