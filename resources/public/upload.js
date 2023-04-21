const errorText = '😬 🔥 ❌ Ooops, something went wrong';
const noSleep = new NoSleep();

const updateStatus = (text) => {
  document.querySelector('#status').innerHTML = text;
};

const updateProgress = (percentage) => {
  const formattedPercentage = Math.floor(100 * percentage);
  updateStatus(`⌛ Uploading… ${formattedPercentage}% done`);
  document.querySelector('progress').value = percentage;
};

const form = document.querySelector('form');
form.addEventListener('submit', (e) => {
  e.preventDefault();
  const data = new FormData();
  const files = document.querySelector('input[type=file]').files;

  if (files.length === 0) {
    alert('Select some files first!');
    return;
  }

  document.querySelector('input[type=file]').style.visibility = 'hidden';
  document.querySelector('input[type=submit]').remove();
  noSleep.enable();

  for (var i = 0; i < files.length; i++) {
    data.append('file', files[i]);
  }

  const request = new XMLHttpRequest();
  request.responseType = 'text';
  request.upload.addEventListener('progress', (e) => {
    updateProgress(e.loaded / e.total);
  });
  request.upload.addEventListener('loadstart', (e) => {
    updateProgress(0);
  });
  request.upload.addEventListener('load', (e) => {
    updateProgress(1);
    noSleep.disable();
  });
  request.upload.addEventListener('abort', (e) => {
    updateStatus(errorText);
    noSleep.disable();
  });
  request.upload.addEventListener('error', (e) => {
    updateStatus(errorText);
    noSleep.disable();
  });
  request.addEventListener('load', (e) => {
    if (e.target.readyState === e.target.DONE) {
      if (e.target.status === 200) {
        updateStatus(`✅ All done! <a href="/">Share more stuff?</a>`);
      } else {
        updateStatus(errorText);
      }
      noSleep.disable();
    }
  });
  request.open('POST', window.location.pathname);
  request.send(data);
});

