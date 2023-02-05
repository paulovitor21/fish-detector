package com.superufam.fishdetector

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Gallery
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.superufam.fishdetector.databinding.ActivityMainBinding
import com.superufam.fishdetector.ml.BirdClassification
import com.superufam.fishdetector.ml.FichsClassification
import org.tensorflow.lite.DataType
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var imageView: ImageView
    private lateinit var button: Button
    private lateinit var tvOutput: TextView
    private val GALLERY_REQUEST_CODE = 123

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        imageView = binding.imageView
        button = binding.btnCaptureImage
        tvOutput = binding.tvOutput
        val buttonLoad = binding.btnLoadImage

        button.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                takePicturePreview.launch(null)
            } else {
                requestPermission.launch(android.Manifest.permission.CAMERA)
            }

        }
        buttonLoad.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                intent.type = "image/*"
                val mimeTypes = arrayOf("image/jpeg", "image/png", "image/jpg")
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                onresult.launch(intent)
            } else {
                requestPermission.launch(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        // redireciona o usuário para o google com o nome cientifico do passáro
        tvOutput.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https:www.google.com/search?q=${tvOutput.text}"))
            startActivity(intent)
        }

        // para baixar a imagem pressionando longamente
        imageView.setOnLongClickListener {
            requestPermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return@setOnLongClickListener true
        }
    }


    // solicitar permissão da câmera
    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        if (granted) {
            takePicturePreview.launch(null)
        } else {
            Toast.makeText(this, "Acesso negado! Tente novamente", Toast.LENGTH_SHORT).show()
        }

    }

    // iniciar camera e tirar foto
    private val takePicturePreview = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) {bitmap->
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap)
            outputGenerator(bitmap)
        }

    }

    // obter imagem da galeria
    private val onresult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        result->
        Log.i("TAG", "Este é o resultado: ${result.data} ${result.resultCode}")
        onResultReceived(GALLERY_REQUEST_CODE, result)
    }

    private fun onResultReceived(requestCode: Int, result: ActivityResult?) {
        when(requestCode) {
            GALLERY_REQUEST_CODE -> {
                if (result?.resultCode == Activity.RESULT_OK) {
                    result.data?.data?.let { uri ->
                        Log.i("TAG", "onResultReceived: $uri")
                        val bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))
                        imageView.setImageBitmap(bitmap)
                        outputGenerator(bitmap)
                    }
                } else {
                    Log.e("TAG", "onActivityResult: erro ao selecionar imagem")
                }
            }
        }
    }

    private fun outputGenerator(bitmap: Bitmap) {
        val birdsModel = BirdClassification.newInstance(this)

        // Converter imagem em tensorflow
        val newBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val tfimage = TensorImage.fromBitmap(newBitmap)

        // Processa a imagem usando o modelo treinado e ordena em ordem descendente
        val outputs = birdsModel.process(tfimage)
            .probabilityAsCategoryList.apply {
                sortByDescending { it.score }
            }
        // obtendo resultado tendo alta probabilidade
        val highProbabilityOutput = outputs[0]

        // selecionando sainda em texto
        tvOutput.text = highProbabilityOutput.label
        Log.i("TAG", "outputGenerator: ${highProbabilityOutput}")
    }

    // download da imagem para o dispositivo
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        isGranted: Boolean ->
        if (isGranted) {
            AlertDialog.Builder(this).setTitle("Baixando imagem?")
                .setMessage("Deseja salva a imagem no dispositivo?")
                .setPositiveButton("Sim"){_, _ ->
                    val drawable:BitmapDrawable = imageView.drawable as BitmapDrawable
                    val bitmap = drawable.bitmap
                    downloadImage(bitmap)
                }
                .setNegativeButton("Não"){dialog, _ ->
                    dialog.dismiss()
                }
                .show()
        } else {
            Toast.makeText(this, "Por favor, dê permissão para download da imagem", Toast.LENGTH_LONG).show()
        }
    }

    // função que pega um bitmap e armazena no dispositivo do usuário
    private fun downloadImage(mBitmap: Bitmap):Uri? {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Birds_Images"+System.currentTimeMillis()/1000)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        }
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        if (uri != null) {
            contentResolver.insert(uri, contentValues)?.also {
                contentResolver.openOutputStream(it).use { outputStream->
                    if (!mBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
                        throw IOException("Não foi possivel salvar o bitmap")
                    } else {
                        Toast.makeText(applicationContext, "Imagem salva!", Toast.LENGTH_LONG).show()
                    }
                }
                return it
            }
        }
        return null
    }
}